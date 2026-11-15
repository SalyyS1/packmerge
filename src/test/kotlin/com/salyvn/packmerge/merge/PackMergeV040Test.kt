package com.salyvn.packmerge.merge

import com.salyvn.packmerge.core.JsonIo
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import javax.imageio.ImageIO

class PackMergeV040Test {

    private fun writePack(root: Path, entries: Map<String, Any>): Path {
        Files.createDirectories(root)
        entries.forEach { (rel, content) ->
            val target = root.resolve(rel)
            Files.createDirectories(target.parent)
            when (content) {
                is String -> Files.writeString(target, content)
                is ByteArray -> Files.write(target, content)
                else -> error("Unsupported fixture content: ${content::class}")
            }
        }
        return root
    }

    private fun encodePng(img: BufferedImage, extraTextChunk: String? = null): ByteArray {
        val raw = ByteArrayOutputStream().use { out ->
            ImageIO.write(img, "png", out)
            out.toByteArray()
        }
        if (extraTextChunk == null) return raw
        // Inject a valid ancillary tEXt chunk right before IEND so raw file bytes differ while RGBA pixels match.
        val iendOffset = raw.size - 12
        val dataBytes = ("Software\u0000$extraTextChunk").toByteArray(Charsets.ISO_8859_1)
        val typeBytes = "tEXt".toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(dataBytes)
        }.value.toInt()
        val chunk = ByteBuffer.allocate(12 + dataBytes.size)
            .putInt(dataBytes.size)
            .put(typeBytes)
            .put(dataBytes)
            .putInt(crc)
            .array()
        return raw.copyOfRange(0, iendOffset) + chunk + raw.copyOfRange(iendOffset, raw.size)
    }

    private fun makeDiagonalSwordSprite(size: Int, hiddenTransparentRgb: Int = 0x000000): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val nx = x.toDouble() / size
                val ny = y.toDouble() / size
                if (kotlin.math.abs(nx - (1.0 - ny)) < 0.22) {
                    val shade = (180 + (70 * nx)).toInt().coerceIn(0, 255)
                    img.setRGB(x, y, (0xFF shl 24) or (shade shl 16) or (210 shl 8) or 250)
                } else {
                    img.setRGB(x, y, hiddenTransparentRgb and 0x00FFFFFF) // alpha = 0
                }
            }
        }
        return img
    }

    private fun makeCheckerRingSprite(size: Int): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val cx = (x - size / 2.0) / (size / 2.0)
                val cy = (y - size / 2.0) / (size / 2.0)
                val r = kotlin.math.sqrt(cx * cx + cy * cy)
                val argb = if (r in 0.35..0.85 && ((x / 2 + y / 2) % 2 == 0)) {
                    Color(240, 90, 40, 255).rgb
                } else {
                    0x00000000
                }
                img.setRGB(x, y, argb)
            }
        }
        return img
    }

    @Test
    fun `perceptual hash detects re-encoded RGBA-identical PNGs and rescaled sprites while separating distinct icons`() {
        val sprite16A = encodePng(makeDiagonalSwordSprite(16, hiddenTransparentRgb = 0x00FF00FF), extraTextChunk = "Blockbench-4.12")
        val sprite16B = encodePng(makeDiagonalSwordSprite(16, hiddenTransparentRgb = 0x00123456))
        val sprite32Scaled = encodePng(makeDiagonalSwordSprite(32))
        val ring16 = encodePng(makeCheckerRingSprite(16))

        assertFalse(sprite16A.contentEquals(sprite16B))
        val fp16A = PerceptualHash.inspect(sprite16A)!!
        val fp16B = PerceptualHash.inspect(sprite16B)!!
        val fp32 = PerceptualHash.inspect(sprite32Scaled)!!
        val fpRing = PerceptualHash.inspect(ring16)!!

        // Transparent RGB garbage under alpha=0 is normalized; pixelSha256 matches identically!
        assertEquals(fp16A.pixelSha256, fp16B.pixelSha256)
        assertEquals(0, PerceptualHash.distance(fp16A, fp16B))

        // Upscaled 16x16 -> 32x32 sprite has near-zero DCT pHash Hamming distance
        val scaledDist = PerceptualHash.distance(fp16A, fp32)
        assertTrue(scaledDist <= 6, "Expected rescaled sprite distance <= 6, got $scaledDist")

        // Distinct icon geometry has wide Hamming separation
        val diffDist = PerceptualHash.distance(fp16A, fpRing)
        assertTrue(diffDist >= 12, "Expected distinct sprite distance >= 12, got $diffDist")
    }

    @Test
    fun `phash coalesces re-encoded same-path PNG overwrites to smallest encoding and flags cross-namespace duplicates`(@TempDir dir: Path) {
        val bloatedPng = encodePng(makeDiagonalSwordSprite(16), extraTextChunk = "VeryLongMetadataBlock".repeat(12))
        val compactPng = encodePng(makeDiagonalSwordSprite(16))
        val scaledClone = encodePng(makeDiagonalSwordSprite(32))

        val packA = writePack(
            dir.resolve("pack-a"),
            mapOf(
                "pack.mcmeta" to """{"pack": {"description": "a", "min_format": 69, "max_format": 97}}""",
                "assets/minecraft/textures/item/relic.png" to bloatedPng,
                "assets/oraxen/textures/item/blade.png" to compactPng,
            ),
        )
        val packB = writePack(
            dir.resolve("pack-b"),
            mapOf(
                "pack.mcmeta" to """{"pack": {"description": "b", "min_format": 69, "max_format": 97}}""",
                "assets/minecraft/textures/item/relic.png" to compactPng,
                "assets/nexo/textures/item/blade_hd.png" to scaledClone,
            ),
        )

        // Note: make compactPng in packA so we also verify smallest-encoding selection even when packA had the smaller one!
        val packASmaller = writePack(
            dir.resolve("pack-a-small"),
            mapOf(
                "pack.mcmeta" to """{"pack": {"description": "a", "min_format": 69, "max_format": 97}}""",
                "assets/minecraft/textures/item/relic.png" to compactPng,
                "assets/oraxen/textures/item/blade.png" to compactPng,
            ),
        )
        val packBBloated = writePack(
            dir.resolve("pack-b-bloat"),
            mapOf(
                "pack.mcmeta" to """{"pack": {"description": "b", "min_format": 69, "max_format": 97}}""",
                "assets/minecraft/textures/item/relic.png" to bloatedPng,
                "assets/nexo/textures/item/blade_hd.png" to scaledClone,
            ),
        )

        val plan = PackMerge(
            listOf(PackSource(packASmaller, 0), PackSource(packBBloated, 1)),
            null,
            null,
            MergeOptions(phash = true, phashThreshold = 6),
        ).plan()

        // Same-path relic.png was coalesced to pack-a-small's compact encoding with PM013 NOTE, not PM006 WARNING!
        val relic = plan.files.single { it.relative == "assets/minecraft/textures/item/relic.png" }
        assertEquals(compactPng.size, relic.bytes.size)
        assertTrue(plan.issues.none { it.path == relic.relative && it.rule == MergeRule.SINGLE_OWNER_OVERWRITE })
        assertTrue(plan.issues.any { it.path == relic.relative && it.rule == MergeRule.PERCEPTUAL_TEXTURE_DUPLICATE && it.level == IssueLevel.NOTE })

        // Cross-namespace blade.png vs blade_hd.png flagged as PM013 WARNING
        assertTrue(plan.issues.any {
            it.path == "assets/nexo/textures/item/blade_hd.png" &&
                it.rule == MergeRule.PERCEPTUAL_TEXTURE_DUPLICATE &&
                it.level == IssueLevel.WARNING
        })
        assertTrue(plan.perceptualDuplicates >= 1)
        // Suppress unused warning on initial packA/packB Paths
        assertNotNull(packA)
        assertNotNull(packB)
    }

    @Test
    fun `dedupeOverlays prunes redundant dual-era overlay assets while preserving intermediate shadowing semantics`(@TempDir dir: Path) {
        val packA = writePack(
            dir.resolve("dual-a"),
            mapOf(
                "pack.mcmeta" to """
                {
                  "pack": { "description": "dual a", "min_format": 46, "max_format": 97, "pack_format": 46, "supported_formats": [46, 64] },
                  "overlays": {
                    "entries": [
                      { "directory": "ov_mid", "min_format": 69, "max_format": 97 },
                      { "directory": "ov_26_3", "min_format": 97, "max_format": 97 },
                      { "directory": "ov_redundant", "min_format": 97, "max_format": 97 }
                    ]
                  }
                }
                """.trimIndent(),
                "assets/minecraft/textures/item/gem.png" to "BASE_GEM_V1",
                "assets/minecraft/textures/item/shield.png" to "BASE_SHIELD_V1",
                // ov_mid overrides shield.png with V2 across 69..97
                "ov_mid/assets/minecraft/textures/item/shield.png" to "MID_SHIELD_V2",
                // ov_26_3 restores shield.png back to BASE_SHIELD_V1 on 97 -> MUST NOT be pruned!
                "ov_26_3/assets/minecraft/textures/item/shield.png" to "BASE_SHIELD_V1",
                // ov_26_3 also copies gem.png unchanged from base (with no ov_mid override) -> MUST BE PRUNED!
                "ov_26_3/assets/minecraft/textures/item/gem.png" to "BASE_GEM_V1",
                // ov_redundant has ONLY gem.png identical to base -> file AND empty overlay entry MUST BE PRUNED!
                "ov_redundant/assets/minecraft/textures/item/gem.png" to "BASE_GEM_V1",
            ),
        )

        val plan = PackMerge(
            listOf(PackSource(packA, 0)),
            null,
            null,
            MergeOptions(dedupeOverlays = true),
        ).plan()

        assertEquals(2, plan.dedupedOverlayFiles)
        assertTrue(plan.savedOverlayBytes > 0)
        assertTrue(plan.files.none { it.relative == "ov_26_3/assets/minecraft/textures/item/gem.png" })
        assertTrue(plan.files.none { it.relative.startsWith("ov_redundant/") })
        // Intermediate-shadowed restoration file is preserved!
        assertNotNull(plan.files.find { it.relative == "ov_26_3/assets/minecraft/textures/item/shield.png" })
        // Empty ov_redundant entry was cleaned from pack.mcmeta overlays.entries
        assertEquals(listOf("ov_mid", "ov_26_3"), plan.overlays.map { it.directory })
        val mcmetaOverlays = JsonIo.parse(String(plan.files.single { it.relative == "pack.mcmeta" }.bytes))
            .jsonObject["overlays"]!!.jsonObject["entries"]!!.jsonArray
        assertEquals(listOf("ov_mid", "ov_26_3"), mcmetaOverlays.map { it.jsonObject["directory"]!!.jsonPrimitive.content })
        assertTrue(plan.issues.any { it.rule == MergeRule.OVERLAY_ASSET_DEDUPLICATED })
    }

    @Test
    fun `pruneOverrides eliminates shadowed legacy predicates and simplifies modern 26_3 dispatch trees`(@TempDir dir: Path) {
        val packA = writePack(
            dir.resolve("prune-a"),
            mapOf(
                "pack.mcmeta" to """{"pack": {"description": "prune a", "min_format": 46, "max_format": 97, "pack_format": 46, "supported_formats": [46, 64]}}""",
                "assets/minecraft/models/item/paper.json" to """
                {
                  "parent": "minecraft:item/generated",
                  "overrides": [
                    { "predicate": { "custom_model_data": 1 }, "model": "minecraft:item/paper" },
                    { "predicate": { "custom_model_data": 2 }, "model": "a:item/shadowed_two" },
                    { "predicate": { "custom_model_data": 10, "pulling": 1 }, "model": "a:item/dead_combo" },
                    { "predicate": { "custom_model_data": 5 }, "model": "a:item/five" },
                    { "predicate": { "custom_model_data": 6 }, "model": "a:item/five" }
                  ]
                }
                """.trimIndent(),
                "assets/minecraft/items/sword.json" to """
                {
                  "model": {
                    "type": "minecraft:select",
                    "property": "minecraft:custom_model_data",
                    "cases": [
                      { "when": "vanilla_alias", "model": { "type": "minecraft:model", "model": "minecraft:item/diamond_sword" } },
                      { "when": "crimson_a", "model": { "type": "minecraft:model", "model": "oraxen:item/crimson" } },
                      { "when": "crimson_b", "model": { "type": "minecraft:model", "model": "oraxen:item/crimson" } }
                    ],
                    "fallback": {
                      "type": "minecraft:range_dispatch",
                      "property": "minecraft:custom_model_data",
                      "entries": [
                        { "threshold": 1, "model": { "type": "minecraft:model", "model": "minecraft:item/diamond_sword" } },
                        { "threshold": 10, "model": { "type": "minecraft:model", "model": "oraxen:item/katana" } },
                        { "threshold": 11, "model": { "type": "minecraft:model", "model": "oraxen:item/katana" } }
                      ],
                      "fallback": {
                        "type": "minecraft:condition",
                        "property": "minecraft:using_item",
                        "on_true": { "type": "minecraft:model", "model": "minecraft:item/diamond_sword" },
                        "on_false": { "type": "minecraft:model", "model": "minecraft:item/diamond_sword" }
                      }
                    }
                  }
                }
                """.trimIndent(),
            ),
        )
        val packB = writePack(
            dir.resolve("prune-b"),
            mapOf(
                "pack.mcmeta" to """{"pack": {"description": "prune b", "min_format": 46, "max_format": 97, "pack_format": 46, "supported_formats": [46, 64]}}""",
                "assets/minecraft/models/item/paper.json" to """
                {
                  "parent": "minecraft:item/generated",
                  "overrides": [
                    { "predicate": { "custom_model_data": 2 }, "model": "b:item/winner_two" },
                    { "predicate": { "custom_model_data": 20 }, "model": "b:item/twenty" }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val plan = PackMerge(
            listOf(PackSource(packA, 0), PackSource(packB, 1)),
            null,
            null,
            MergeOptions(pruneOverrides = true),
        ).plan()

        // Legacy paper.json:
        // - cmd=1 -> minecraft:item/paper self-override pruned
        // - cmd=2 (a:item/shadowed_two) shadowed by packB's cmd=2 (b:item/winner_two) -> pruned!
        // - {cmd=10, pulling=1} shadowed by later {cmd=5} -> pruned!
        // - cmd=5 -> a:item/five shadowed by packB's later cmd=2 -> wait: packB's cmd=2 has 2 <= 5, so in concatenated
        //   order [a's overrides, b's cmd=2, b's cmd=20], b's cmd=2 at index 5 dominates a's cmd=5 and cmd=6!
        val paperOverrides = JsonIo.parse(String(plan.files.single { it.relative == "assets/minecraft/models/item/paper.json" }.bytes))
            .jsonObject["overrides"]!!.jsonArray
        assertEquals(
            listOf("b:item/winner_two", "b:item/twenty"),
            paperOverrides.map { it.jsonObject["model"]!!.jsonPrimitive.content },
        )

        // Modern sword.json:
        // - condition(on_true == on_false) folded to model(diamond_sword)
        // - range_dispatch threshold=1 (== fallback diamond_sword) and threshold=11 (== threshold=10 katana) pruned, leaving only threshold=10!
        // - select "vanilla_alias"? Note select fallback is the range_dispatch object (not bare diamond_sword), while "crimson_a" + "crimson_b" coalesce!
        val swordModel = JsonIo.parse(String(plan.files.single { it.relative == "assets/minecraft/items/sword.json" }.bytes))
            .jsonObject["model"]!!.jsonObject
        val selectCases = swordModel["cases"]!!.jsonArray
        assertEquals(2, selectCases.size)
        val coalescedCrimson = selectCases.last().jsonObject["when"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("crimson_a", "crimson_b"), coalescedCrimson)

        val rangeFallback = swordModel["fallback"]!!.jsonObject
        val rangeEntries = rangeFallback["entries"]!!.jsonArray
        assertEquals(1, rangeEntries.size)
        assertEquals("10", rangeEntries.single().jsonObject["threshold"]!!.jsonPrimitive.content)
        // Nested condition was folded to plain minecraft:model
        assertEquals("minecraft:model", rangeFallback["fallback"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        assertTrue(plan.prunedOverrides >= 5)
        assertTrue(plan.issues.any { it.rule == MergeRule.MODEL_OVERRIDE_PRUNED })
    }

    @Test
    fun `validates Minecraft 26_3 sound event atlas schema, event reference cycles, replace wipes, and ogg resolution`(@TempDir dir: Path) {
        val packA = writePack(
            dir.resolve("sfx-a"),
            mapOf(
                "pack.mcmeta" to """
                {
                  "pack": { "description": "sfx a", "min_format": 69, "max_format": 97 },
                  "overlays": { "entries": [ { "directory": "v26_3", "min_format": 97, "max_format": 97 } ] }
                }
                """.trimIndent(),
                "assets/oraxen/sounds.json" to """
                {
                  "boss.roar": { "sounds": ["oraxen:boss/roar"] },
                  "BadUpperKey": { "sounds": ["oraxen:ok"] },
                  "ext.trap": { "sounds": ["oraxen:sfx/bell.ogg"] },
                  "bad.pitch": { "sounds": [ { "name": "oraxen:boss/roar", "pitch": 0, "volume": -1, "weight": 0 } ] },
                  "cycle.a": { "sounds": [ { "name": "oraxen:cycle.b", "type": "event" } ] },
                  "cycle.b": { "sounds": [ { "name": "oraxen:cycle.a", "type": "event" } ] },
                  "dangling.evt": { "sounds": [ { "name": "oraxen:nonexistent", "type": "event" } ] },
                  "overlay.only": { "sounds": ["oraxen:v26/chime"] },
                  "missing.audio": { "sounds": ["oraxen:boss/missing_clip"] },
                  "vanilla.alias": { "sounds": [ { "name": "minecraft:block.note_block.pling", "type": "event" } ] }
                }
                """.trimIndent(),
                "assets/oraxen/sounds/boss/roar.ogg" to ByteArray(16) { 0x4F },
                "v26_3/assets/oraxen/sounds/v26/chime.ogg" to ByteArray(16) { 0x4F },
            ),
        )
        val packB = writePack(
            dir.resolve("sfx-b"),
            mapOf(
                "pack.mcmeta" to """{"pack": {"description": "sfx b", "min_format": 69, "max_format": 97}}""",
                "assets/oraxen/sounds.json" to """
                {
                  "boss.roar": { "replace": true, "sounds": ["oraxen:boss/roar"] }
                }
                """.trimIndent(),
            ),
        )

        val plan = PackMerge(
            listOf(PackSource(packA, 0), PackSource(packB, 1)),
            null,
            null,
            MergeOptions(auditSounds = true),
        ).plan()

        val issues = plan.issues
        // PM016 schema errors: BadUpperKey, .ogg extension trap, invalid pitch/volume/weight
        assertTrue(issues.any { it.rule == MergeRule.SOUND_ATLAS_INVALID_SCHEMA && "BadUpperKey" in it.message })
        assertTrue(issues.any { it.rule == MergeRule.SOUND_ATLAS_INVALID_SCHEMA && ".ogg" in it.message })
        assertTrue(issues.any { it.rule == MergeRule.SOUND_ATLAS_INVALID_SCHEMA && "volume" in it.message })
        assertTrue(issues.any { it.rule == MergeRule.SOUND_ATLAS_INVALID_SCHEMA && "pitch" in it.message })
        assertTrue(issues.any { it.rule == MergeRule.SOUND_ATLAS_INVALID_SCHEMA && "weight" in it.message })

        // PM017 circular type=event chain detected
        assertTrue(issues.any { it.rule == MergeRule.SOUND_EVENT_REFERENCE_CYCLE && "oraxen:cycle.a" in it.message && "oraxen:cycle.b" in it.message })

        // PM018 replace=true wipe warning, missing_clip.ogg warning, and dangling event warning
        assertTrue(issues.any { it.rule == MergeRule.SOUND_MISSING_OGG_OR_WIPE && "replace=true" in it.message && "boss.roar" in it.message })
        assertTrue(issues.any { it.rule == MergeRule.SOUND_MISSING_OGG_OR_WIPE && "assets/oraxen/sounds/boss/missing_clip.ogg" in it.message })
        assertTrue(issues.any { it.rule == MergeRule.SOUND_MISSING_OGG_OR_WIPE && "oraxen:nonexistent" in it.message })

        // Present in root (`roar.ogg`), present in 26.3 overlay (`v26/chime.ogg`), and vanilla event redirect (`minecraft:block.note_block.pling`) are NOT flagged!
        assertNull(issues.find { it.rule == MergeRule.SOUND_MISSING_OGG_OR_WIPE && "roar.ogg" in it.message })
        assertNull(issues.find { it.rule == MergeRule.SOUND_MISSING_OGG_OR_WIPE && "chime.ogg" in it.message })
        assertNull(issues.find { it.rule == MergeRule.SOUND_MISSING_OGG_OR_WIPE && "note_block.pling" in it.message })
    }
}
