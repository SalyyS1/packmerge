package com.salyvn.packmerge.merge

import com.salyvn.packmerge.core.JsonIo
import com.salyvn.packmerge.core.OverlayEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Minecraft `1.20.1..26.3` `sounds.json` Sound Event Atlas merger, schema validator, `"type": "event"` cycle
 * detector, `"replace": true` wipe warner, and optional `.ogg` file existence auditor (`--audit-sounds`).
 */
object SoundAtlasValidator {
    private val NAMESPACE_REGEX = Regex("^[a-z0-9_.-]+$")
    private val EVENT_KEY_REGEX = Regex("^[a-z0-9_.-]+$")
    private val SOUND_PATH_REGEX = Regex("^[a-z0-9_/.-]+$")

    /**
     * Merges `sounds.json` across multiple packs while warning on both key overwrites (`PM007`) and
     * destructive `"replace": true` sound-pool wipes (`PM018`).
     */
    fun mergeSounds(
        relative: String,
        sources: List<PackSource>,
        report: MergeReport,
    ): ByteArray? {
        val merged = linkedMapOf<String, JsonElement>()
        val owner = mutableMapOf<String, String>()
        val replaced = mutableListOf<String>()

        sources.forEach { source ->
            val root = try {
                JsonIo.parse(source.readText(relative)).jsonObject
            } catch (failure: Exception) {
                report.error(
                    relative,
                    "${source.name} does not contain a readable object (${failure.message}); it was left out of the merge.",
                    MergeRule.UNMERGEABLE_ASSET_JSON,
                )
                return@forEach
            }
            root.forEach { (key, value) ->
                val previous = merged.put(key, value)
                val replaceFlag = ((value as? JsonObject)?.get("replace") as? JsonPrimitive)?.booleanOrNull == true
                if (previous != null && previous != value) {
                    replaced += "$key (${owner[key]} -> ${source.name})"
                    if (replaceFlag) {
                        report.warn(
                            relative,
                            "Sound event '$key' in ${source.name} sets replace=true while overriding ${owner[key]}, wiping lower-priority sound pools.",
                            MergeRule.SOUND_MISSING_OGG_OR_WIPE,
                        )
                    }
                } else if (replaceFlag) {
                    report.warn(
                        relative,
                        "Sound event '$key' in ${source.name} sets replace=true, discarding underlying vanilla or lower-pack sound pools.",
                        MergeRule.SOUND_MISSING_OGG_OR_WIPE,
                    )
                }
                owner[key] = source.name
            }
        }
        if (merged.isEmpty()) return null

        if (replaced.isNotEmpty()) {
            val shown = replaced.take(6).joinToString(", ")
            val rest = if (replaced.size > 6) ", and ${replaced.size - 6} more" else ""
            val message = "${replaced.size} key(s) are defined differently by more than one pack; the later pack's value is the one kept: $shown$rest"
            report.warn(relative, message, MergeRule.SOUND_EVENT_OVERWRITE)
        }
        report.note(relative, "${merged.size} key(s) from ${sources.size} packs.", MergeRule.ADDITIVE_MERGE_SUMMARY)
        return JsonIo.render(JsonObject(merged)).toByteArray()
    }

    private data class EventEdge(
        val sourceFile: String,
        val fromId: String,
        val toId: String,
    )

    private data class SoundFileRef(
        val sourceFile: String,
        val eventId: String,
        val soundNamespace: String,
        val soundPath: String,
        val overlayDir: String?,
    )

    /**
     * Audits every `sounds.json` in the compiled plan (both root `assets/<ns>/sounds.json` and
     * `<overlay>/assets/<ns>/sounds.json`) for Minecraft `26.3` schema compliance (`PM016`), circular
     * `"type": "event"` chains (`PM017`), and missing `.ogg` / dangling event assets (`PM018` under `--audit-sounds`).
     */
    fun validateAll(
        files: List<MergedFile>,
        overlays: List<OverlayEntry>,
        auditMissingAssets: Boolean,
        report: MergeReport,
    ) {
        val overlayDirs = overlays.map { it.directory }.toSet()
        val allPaths = files.map { it.relative }.toSet()

        val definedEvents = linkedSetOf<String>()
        val eventEdges = mutableListOf<EventEdge>()
        val fileRefs = mutableListOf<SoundFileRef>()

        for (file in files) {
            if (file.kind != MergeKind.SOUNDS) continue
            val (overlayDir, namespace) = extractSoundScope(file.relative, overlayDirs) ?: continue
            val root = try {
                JsonIo.parse(String(file.bytes, Charsets.UTF_8)).jsonObject
            } catch (failure: Exception) {
                report.error(
                    file.relative,
                    "Malformed sounds.json (${failure.message}).",
                    MergeRule.SOUND_ATLAS_INVALID_SCHEMA,
                )
                continue
            }

            // Single-owner sounds.json files did not go through mergeSounds; check replace=true on them too when auditing.
            val checkSingleReplace = !file.merged && auditMissingAssets

            for ((eventKey, rawDef) in root) {
                if (!EVENT_KEY_REGEX.matches(eventKey)) {
                    report.error(
                        file.relative,
                        "Invalid sound event key '$eventKey': keys in sounds.json must match [a-z0-9_.-]+ (no uppercase, spaces, or colons).",
                        MergeRule.SOUND_ATLAS_INVALID_SCHEMA,
                    )
                    continue
                }
                val fullEventId = "$namespace:$eventKey"
                definedEvents += fullEventId

                val defObj = rawDef as? JsonObject
                if (defObj == null) {
                    report.error(
                        file.relative,
                        "Sound event '$eventKey' must map to a JSON object.",
                        MergeRule.SOUND_ATLAS_INVALID_SCHEMA,
                    )
                    continue
                }

                defObj["replace"]?.let { repEl ->
                    val repBool = (repEl as? JsonPrimitive)?.booleanOrNull
                    if (repBool == null) {
                        report.error(
                            file.relative,
                            "Sound event '$eventKey' has non-boolean 'replace' field.",
                            MergeRule.SOUND_ATLAS_INVALID_SCHEMA,
                        )
                    } else if (repBool && checkSingleReplace) {
                        report.warn(
                            file.relative,
                            "Sound event '$eventKey' sets replace=true, discarding lower-pack sound pools.",
                            MergeRule.SOUND_MISSING_OGG_OR_WIPE,
                        )
                    }
                }

                defObj["subtitle"]?.let { subEl ->
                    if (subEl !is JsonPrimitive || !subEl.isString) {
                        report.error(
                            file.relative,
                            "Sound event '$eventKey' has non-string 'subtitle' field.",
                            MergeRule.SOUND_ATLAS_INVALID_SCHEMA,
                        )
                    }
                }

                val soundsEl = defObj["sounds"]
                if (soundsEl != null && soundsEl !is JsonArray) {
                    report.error(
                        file.relative,
                        "Sound event '$eventKey' must declare 'sounds' as a JSON array.",
                        MergeRule.SOUND_ATLAS_INVALID_SCHEMA,
                    )
                    continue
                }

                val soundsArr = soundsEl ?: continue
                for (entryEl in soundsArr) {
                    validateSoundEntry(
                        sourcePath = file.relative,
                        defaultNamespace = namespace,
                        overlayDir = overlayDir,
                        fullEventId = fullEventId,
                        entryEl = entryEl,
                        eventEdges = eventEdges,
                        fileRefs = fileRefs,
                        report = report,
                    )
                }
            }
        }

        detectEventCycles(eventEdges, report)

        if (auditMissingAssets) {
            auditSoundReferences(fileRefs, eventEdges, definedEvents, allPaths, overlayDirs, report)
        }
    }

    private fun validateSoundEntry(
        sourcePath: String,
        defaultNamespace: String,
        overlayDir: String?,
        fullEventId: String,
        entryEl: JsonElement,
        eventEdges: MutableList<EventEdge>,
        fileRefs: MutableList<SoundFileRef>,
        report: MergeReport,
    ) {
        when (entryEl) {
            is JsonPrimitive -> {
                if (!entryEl.isString) {
                    report.error(
                        sourcePath,
                        "Sound entry in '$fullEventId' must be a string identifier or object, got '$entryEl'.",
                        MergeRule.SOUND_ATLAS_INVALID_SCHEMA,
                    )
                    return
                }
                val parsed = validateSoundLocation(sourcePath, fullEventId, entryEl.content, defaultNamespace, isEventRef = false, report = report)
                    ?: return
                fileRefs += SoundFileRef(sourcePath, fullEventId, parsed.first, parsed.second, overlayDir)
            }
            is JsonObject -> {
                val namePrim = entryEl["name"] as? JsonPrimitive
                if (namePrim == null || !namePrim.isString || namePrim.content.isBlank()) {
                    report.error(
                        sourcePath,
                        "Object sound entry in '$fullEventId' is missing a string 'name' field.",
                        MergeRule.SOUND_ATLAS_INVALID_SCHEMA,
                    )
                    return
                }

                val typeVal = (entryEl["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "file"
                if (entryEl["type"] != null && (entryEl["type"] !is JsonPrimitive || !(entryEl["type"] as JsonPrimitive).isString || (typeVal != "file" && typeVal != "event"))) {
                    report.error(
                        sourcePath,
                        "Sound entry '${namePrim.content}' in '$fullEventId' has invalid type '$typeVal'; expected 'file' or 'event'.",
                        MergeRule.SOUND_ATLAS_INVALID_SCHEMA,
                    )
                    return
                }

                entryEl["volume"]?.let { v ->
                    val num = numericDouble(v)
                    if (num == null || num <= 0.0) {
                        report.error(
                            sourcePath,
                            "Sound entry '${namePrim.content}' in '$fullEventId' has invalid volume '$v' (must be > 0.0).",
                            MergeRule.SOUND_ATLAS_INVALID_SCHEMA,
                        )
                    }
                }

                entryEl["pitch"]?.let { p ->
                    val num = numericDouble(p)
                    if (num == null || num <= 0.0) {
                        report.error(
                            sourcePath,
                            "Sound entry '${namePrim.content}' in '$fullEventId' has invalid pitch '$p' (must be > 0.0).",
                            MergeRule.SOUND_ATLAS_INVALID_SCHEMA,
                        )
                    }
                }

                entryEl["weight"]?.let { w ->
                    val num = numericInt(w)
                    if (num == null || num < 1) {
                        report.error(
                            sourcePath,
                            "Sound entry '${namePrim.content}' in '$fullEventId' has invalid weight '$w' (must be integer >= 1).",
                            MergeRule.SOUND_ATLAS_INVALID_SCHEMA,
                        )
                    }
                }

                entryEl["attenuation_distance"]?.let { a ->
                    val num = numericInt(a)
                    if (num == null || num < 0) {
                        report.error(
                            sourcePath,
                            "Sound entry '${namePrim.content}' in '$fullEventId' has invalid attenuation_distance '$a' (must be integer >= 0).",
                            MergeRule.SOUND_ATLAS_INVALID_SCHEMA,
                        )
                    }
                }

                for (boolField in listOf("stream", "preload")) {
                    entryEl[boolField]?.let { b ->
                        if ((b as? JsonPrimitive)?.booleanOrNull == null) {
                            report.error(
                                sourcePath,
                                "Sound entry '${namePrim.content}' in '$fullEventId' has non-boolean '$boolField' field.",
                                MergeRule.SOUND_ATLAS_INVALID_SCHEMA,
                            )
                        }
                    }
                }

                val isEvent = typeVal == "event"
                val parsed = validateSoundLocation(sourcePath, fullEventId, namePrim.content, defaultNamespace, isEventRef = isEvent, report = report)
                    ?: return
                if (isEvent) {
                    eventEdges += EventEdge(sourcePath, fullEventId, "${parsed.first}:${parsed.second}")
                } else {
                    fileRefs += SoundFileRef(sourcePath, fullEventId, parsed.first, parsed.second, overlayDir)
                }
            }
            else -> {
                report.error(
                    sourcePath,
                    "Sound entry in '$fullEventId' must be a string or JSON object.",
                    MergeRule.SOUND_ATLAS_INVALID_SCHEMA,
                )
            }
        }
    }

    private fun validateSoundLocation(
        sourcePath: String,
        fullEventId: String,
        rawLocation: String,
        defaultNamespace: String,
        isEventRef: Boolean,
        report: MergeReport,
    ): Pair<String, String>? {
        if (!isEventRef && rawLocation.endsWith(".ogg", ignoreCase = true)) {
            report.error(
                sourcePath,
                "Sound entry '$rawLocation' in '$fullEventId' must omit the '.ogg' extension; Minecraft appends '.ogg' automatically.",
                MergeRule.SOUND_ATLAS_INVALID_SCHEMA,
            )
            return null
        }

        val parts = rawLocation.split(':')
        if (parts.size > 2) {
            report.error(
                sourcePath,
                "Sound entry '$rawLocation' in '$fullEventId' is not a valid resource location.",
                MergeRule.SOUND_ATLAS_INVALID_SCHEMA,
            )
            return null
        }

        val ns = if (parts.size == 2) parts[0] else defaultNamespace
        val path = if (parts.size == 2) parts[1] else parts[0]

        if (!NAMESPACE_REGEX.matches(ns)) {
            report.error(
                sourcePath,
                "Sound entry '$rawLocation' in '$fullEventId' uses invalid namespace '$ns' (must match [a-z0-9_.-]+).",
                MergeRule.SOUND_ATLAS_INVALID_SCHEMA,
            )
            return null
        }

        val validTarget = if (isEventRef) {
            EVENT_KEY_REGEX.matches(path)
        } else {
            SOUND_PATH_REGEX.matches(path) && !path.startsWith('/') && !path.endsWith('/') && "//" !in path &&
                path.split('/').none { it == "." || it == ".." }
        }
        if (!validTarget) {
            report.error(
                sourcePath,
                "Sound entry '$rawLocation' in '$fullEventId' uses invalid path '$path' (must be lowercase resource identifier).",
                MergeRule.SOUND_ATLAS_INVALID_SCHEMA,
            )
            return null
        }

        return ns to path
    }

    private fun detectEventCycles(edges: List<EventEdge>, report: MergeReport) {
        if (edges.isEmpty()) return
        val adj = linkedMapOf<String, MutableList<EventEdge>>()
        for (edge in edges) {
            adj.getOrPut(edge.fromId) { mutableListOf() } += edge
        }

        val state = mutableMapOf<String, Int>() // 0=unvisited, 1=visiting, 2=visited
        val stack = mutableListOf<String>()
        val reportedCycles = mutableSetOf<String>()

        fun dfs(node: String, entryFile: String) {
            state[node] = 1
            stack += node
            for (edge in adj[node].orEmpty()) {
                val next = edge.toId
                when (state[next] ?: 0) {
                    0 -> dfs(next, edge.sourceFile)
                    1 -> {
                        val cycleStart = stack.indexOf(next)
                        val chain = if (cycleStart >= 0) stack.subList(cycleStart, stack.size) + next else listOf(node, next)
                        val canonical = chain.dropLast(1).sorted().joinToString("->")
                        if (reportedCycles.add(canonical)) {
                            report.error(
                                edge.sourceFile,
                                "Circular type=event sound reference chain: ${chain.joinToString(" -> ")}.",
                                MergeRule.SOUND_EVENT_REFERENCE_CYCLE,
                            )
                        }
                    }
                }
            }
            stack.removeAt(stack.lastIndex)
            state[node] = 2
        }

        for (node in adj.keys) {
            if ((state[node] ?: 0) == 0) {
                dfs(node, adj[node]!!.first().sourceFile)
            }
        }
    }

    private fun auditSoundReferences(
        fileRefs: List<SoundFileRef>,
        eventEdges: List<EventEdge>,
        definedEvents: Set<String>,
        allPaths: Set<String>,
        overlayDirs: Set<String>,
        report: MergeReport,
    ) {
        for (ref in fileRefs) {
            val baseOgg = "assets/${ref.soundNamespace}/sounds/${ref.soundPath}.ogg"
            val foundInBase = baseOgg in allPaths
            val foundInOverlay = if (ref.overlayDir != null) {
                "${ref.overlayDir}/$baseOgg" in allPaths || overlayDirs.any { "$it/$baseOgg" in allPaths }
            } else {
                overlayDirs.any { "$it/$baseOgg" in allPaths }
            }
            // Skip flagging vanilla `minecraft:*` sound files unless no vanilla fallback is expected?
            // Under explicit `--audit-sounds`, custom namespaces must always exist; vanilla `minecraft:*` sound paths
            // are only flagged if the event itself is in a custom namespace AND points to a non-existent custom path,
            // wait: packs commonly reference vanilla `minecraft:random/orb` in custom events! So only flag missing `.ogg`
            // when `ref.soundNamespace != "minecraft"`, OR when `assets/minecraft/sounds/` exists in the pack for that prefix!
            val shouldRequireOgg = ref.soundNamespace != "minecraft" ||
                allPaths.any { it.startsWith("assets/minecraft/sounds/") || overlayDirs.any { ov -> it.startsWith("$ov/assets/minecraft/sounds/") } }
            if (shouldRequireOgg && !foundInBase && !foundInOverlay) {
                report.warn(
                    ref.sourceFile,
                    "Sound event '${ref.eventId}' references missing audio file '$baseOgg'.",
                    MergeRule.SOUND_MISSING_OGG_OR_WIPE,
                )
            }
        }

        for (edge in eventEdges) {
            val targetNs = edge.toId.substringBefore(':')
            if (targetNs != "minecraft" && edge.toId !in definedEvents) {
                report.warn(
                    edge.sourceFile,
                    "Sound event '${edge.fromId}' redirects via type=event to undefined custom event '${edge.toId}'.",
                    MergeRule.SOUND_MISSING_OGG_OR_WIPE,
                )
            }
        }
    }

    private fun extractSoundScope(relative: String, overlayDirs: Set<String>): Pair<String?, String>? {
        val parts = relative.split('/')
        return if (parts.size == 4 && parts[0] in overlayDirs && parts[1] == "assets" && parts[3] == "sounds.json") {
            parts[0] to parts[2]
        } else if (parts.size == 3 && parts[0] == "assets" && parts[2] == "sounds.json") {
            null to parts[1]
        } else {
            null
        }
    }

    private fun numericDouble(el: JsonElement): Double? {
        val prim = el as? JsonPrimitive ?: return null
        if (prim.isString) return null
        return prim.content.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun numericInt(el: JsonElement): Int? {
        val prim = el as? JsonPrimitive ?: return null
        if (prim.isString) return null
        return prim.content.toIntOrNull()
    }
}
