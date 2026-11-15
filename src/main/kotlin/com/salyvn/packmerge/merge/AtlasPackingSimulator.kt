package com.salyvn.packmerge.merge

import com.salyvn.packmerge.core.JsonIo
import com.salyvn.packmerge.core.OverlayEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Minecraft 1.20.1..26.3 texture atlas packing simulation and cross-namespace sprite conflict resolver.
 *
 * Rules:
 *   PM019 - CrossNamespaceAtlasConflict : two packs contribute `single` or `sprite` sources for the same
 *           resolved sprite ID from different namespaces, leaving earlier sprites unreachable in the
 *           final compiled atlas.
 *   PM020 - AtlasSourceMissingSpriteRef : an atlas `single` source references a texture path that is not
 *           present in any input pack (checked under `--audit-atlas`).
 */
object AtlasPackingSimulator {

    private val ATLAS_PATTERN = Regex("""^(?:[a-z0-9_.-]+/)?assets/([a-z0-9_.-]+)/atlases/([a-z0-9_./]+\.json)$""")
    private const val MAX_CONFLICT_WARNINGS = 30

    /**
     * Scans all atlas files in [files], resolves every `single` and `sprite` source entry to a canonical
     * sprite ID, and reports cross-namespace duplicates (PM019) and missing sprite references (PM020 when
     * [auditAtlas] is true).
     */
    fun validateAll(
        files: List<MergedFile>,
        overlays: List<OverlayEntry>,
        auditAtlas: Boolean,
        report: MergeReport,
    ) {
        val overlayDirs = overlays.map { it.directory }.toSet()
        val allPaths = files.map { it.relative }.toSet()

        // Collect all sprite IDs contributed per atlas file, tracking their source namespace
        // atlas key -> list of (spriteId, sourceNamespace, filePath, packName)
        val atlasSprites = linkedMapOf<String, MutableList<SpriteEntry>>()

        for (file in files) {
            val (atlasKey, namespace) = resolveAtlasScope(file.relative, overlayDirs) ?: continue
            val sources = parseSources(file) ?: continue
            val existing = atlasSprites.getOrPut(atlasKey) { mutableListOf() }

            for (source in sources) {
                val spriteIds = resolveSourceToSpriteIds(source, namespace) ?: continue
                for (spriteId in spriteIds) {
                    existing += SpriteEntry(spriteId, namespace, file.relative, source)
                }
            }
        }

        // Detect PM019: same sprite ID claimed by different namespaces in the same atlas
        var conflictCount = 0
        for ((atlasKey, entries) in atlasSprites) {
            // group by sprite ID
            val bySprite = linkedMapOf<String, MutableList<SpriteEntry>>()
            for (entry in entries) {
                bySprite.getOrPut(entry.spriteId) { mutableListOf() } += entry
            }
            for ((spriteId, claims) in bySprite) {
                val namespaces = claims.map { it.namespace }.distinct()
                if (namespaces.size < 2) continue
                if (conflictCount >= MAX_CONFLICT_WARNINGS) {
                    report.warn(
                        atlasKey,
                        "Additional cross-namespace atlas sprite conflicts omitted (${atlasSprites.values.sumOf { it.size }} total entries). Use --audit-atlas for full detail.",
                        MergeRule.CROSS_NAMESPACE_ATLAS_CONFLICT,
                    )
                    return
                }
                conflictCount++
                val detail = namespaces.joinToString(" vs ") { ns ->
                    val path = claims.first { it.namespace == ns }.filePath
                    "$ns (from $path)"
                }
                report.warn(
                    atlasKey,
                    "Sprite '$spriteId' is registered in atlas '$atlasKey' by ${namespaces.size} packs under conflicting namespaces: $detail; earlier registrations are shadowed.",
                    MergeRule.CROSS_NAMESPACE_ATLAS_CONFLICT,
                )
            }
        }

        // Detect PM020: single-source texture references that do not exist in the final merged pack
        if (!auditAtlas) return
        for ((_, entries) in atlasSprites) {
            for (entry in entries) {
                val sourceObj = entry.rawSource as? JsonObject ?: continue
                val type = (sourceObj["type"] as? JsonPrimitive)?.content ?: continue
                if (type != "single") continue
                val resource = (sourceObj["resource"] as? JsonPrimitive)?.content ?: continue
                val (textureNs, texturePath) = resource.split(":").let {
                    if (it.size == 2) it[0] to it[1] else "minecraft" to resource
                }
                val expected = "assets/$textureNs/textures/$texturePath.png"
                val foundInAny = allPaths.contains(expected) ||
                    overlayDirs.any { allPaths.contains("$it/$expected") }
                if (!foundInAny) {
                    report.warn(
                        entry.filePath,
                        "Atlas single source references missing texture '$expected'; the sprite will not appear in the compiled atlas.",
                        MergeRule.ATLAS_SOURCE_MISSING_SPRITE_REF,
                    )
                }
            }
        }
    }

    private data class SpriteEntry(
        val spriteId: String,
        val namespace: String,
        val filePath: String,
        val rawSource: Any,
    )

    /**
     * Returns (atlasKey, namespace) for a path that is an atlas file, or null otherwise.
     * atlasKey is the logical atlas name, e.g. "blocks" for `assets/<ns>/atlases/blocks.json`.
     */
    private fun resolveAtlasScope(relative: String, overlayDirs: Set<String>): Pair<String, String>? {
        val parts = relative.split('/')
        // root: assets/<ns>/atlases/<name>.json
        if (parts.size >= 4 && parts[0] == "assets" && parts[2] == "atlases" && relative.endsWith(".json")) {
            val ns = parts[1]
            val atlasName = parts.drop(3).joinToString("/").removeSuffix(".json")
            return "atlases/$atlasName" to ns
        }
        // overlay: <dir>/assets/<ns>/atlases/<name>.json
        if (parts.size >= 5 && parts[0] in overlayDirs && parts[1] == "assets" && parts[3] == "atlases" && relative.endsWith(".json")) {
            val ns = parts[2]
            val atlasName = parts.drop(4).joinToString("/").removeSuffix(".json")
            return "atlases/$atlasName" to ns
        }
        return null
    }

    private fun parseSources(file: MergedFile): List<JsonObject>? {
        return try {
            val root = JsonIo.parse(String(file.bytes)).jsonObject
            (root["sources"] as? JsonArray)?.mapNotNull { it as? JsonObject }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves a JSON atlas source entry to the set of sprite IDs it contributes.
     * Only `single` and `sprite` sources are simulated (directory sources are too broad to enumerate here).
     */
    private fun resolveSourceToSpriteIds(source: JsonObject, defaultNamespace: String): List<String>? {
        val type = (source["type"] as? JsonPrimitive)?.content ?: return null
        return when (type) {
            "single" -> {
                val resource = (source["resource"] as? JsonPrimitive)?.content ?: return null
                val spriteId = resolveResourceId(resource, defaultNamespace)
                listOf(spriteId)
            }
            "sprite" -> {
                // sprite sources list explicit texture paths
                val resources = (source["resources"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content } ?: return null
                resources.map { resolveResourceId(it, defaultNamespace) }
            }
            else -> null
        }
    }

    private fun resolveResourceId(resource: String, defaultNamespace: String): String {
        return if (resource.contains(':')) resource
        else "$defaultNamespace:$resource"
    }
}
