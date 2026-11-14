package com.salyvn.packmerge.merge

import com.salyvn.packmerge.core.JsonIo
import com.salyvn.packmerge.core.PackFormat
import com.salyvn.packmerge.core.PackFormats
import com.salyvn.packmerge.core.PackMeta
import com.salyvn.packmerge.core.PackMetaException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PackMergeTest {

    private fun plan(): MergePlan {
        val a = PackSource(Path.of("src/test/resources/plugin-a"), 0)
        val b = PackSource(Path.of("src/test/resources/plugin-b"), 1)
        return PackMerge(listOf(a, b), null, null).plan()
    }

    private fun MergePlan.file(relative: String): JsonObject =
        JsonIo.parse(String(files.single { it.relative == relative }.bytes)).jsonObject

    private fun writePack(root: Path, entries: Map<String, String>): Path {
        Files.createDirectories(root)
        entries.forEach { (rel, content) ->
            val target = root.resolve(rel)
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }
        return root
    }

    @Test
    fun `overrides from both packs end up in one list`() {
        val plan = plan()
        val overrides = plan.file("assets/minecraft/models/item/paper.json")["overrides"] as JsonArray
        assertEquals(4, overrides.size)
        assertEquals(
            listOf("a:item/scroll", "a:item/note", "b:item/map", "b:item/gem"),
            overrides.map { it.jsonObject["model"]!!.jsonPrimitive.content },
        )
        assertEquals("minecraft:item/generated", plan.file("assets/minecraft/models/item/paper.json")["parent"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a custom model data value two packs claim is reported as unreachable`() {
        val errors = plan().issues.filter { it.level == IssueLevel.ERROR }
        assertTrue(errors.any { it.rule == MergeRule.LEGACY_OVERRIDE_COLLISION && "custom_model_data=2" in it.message && "plugin-a" in it.message && "plugin-b" in it.message })
    }

    @Test
    fun `item model definitions are unioned by threshold and sorted`() {
        val model = plan().file("assets/minecraft/items/bow.json")["model"]!!.jsonObject
        assertEquals("minecraft:custom_model_data", model["property"]!!.jsonPrimitive.content)
        val entries = model["entries"]!!.jsonArray
        assertEquals(listOf("1", "7"), entries.map { it.jsonObject["threshold"]!!.jsonPrimitive.content })
        assertEquals("b:item/warbow", entries.first().jsonObject["model"]!!.jsonObject["model"]!!.jsonPrimitive.content)
        assertTrue(plan().issues.any { it.rule == MergeRule.ITEM_DEFINITION_COLLISION && "custom_model_data 1" in it.message })
    }

    @Test
    fun `font providers are concatenated and the shared codepoint is an error`() {
        val plan = plan()
        val providers = plan.file("assets/minecraft/font/default.json")["providers"]!!.jsonArray
        assertEquals(5, providers.size)
        assertEquals("space", providers.first().jsonObject["type"]!!.jsonPrimitive.content)
        val issues = plan.issues.filter { it.path == "assets/minecraft/font/default.json" }
        assertTrue(issues.any { it.rule == MergeRule.FONT_CODEPOINT_COLLISION && "U+F010" in it.message }, issues.toString())
        assertTrue(issues.none { "U+F001" in it.message || "U+F020" in it.message })
        assertTrue(issues.any { it.level == IssueLevel.NOTE && "reference, ttf or unihex" in it.message })
    }

    @Test
    fun `sounds and translations are key unions, and only the sound clash is a warning`() {
        val plan = plan()
        val sounds = plan.file("assets/minecraft/sounds.json")
        assertEquals(setOf("a.click", "shared.hit", "b.pop"), sounds.keys)
        assertTrue(plan.issues.any { it.path == "assets/minecraft/sounds.json" && it.rule == MergeRule.SOUND_EVENT_OVERWRITE && "shared.hit" in it.message })

        val lang = plan.file("assets/minecraft/lang/en_us.json")
        assertEquals(setOf("item.a.scroll", "shared.key", "item.b.gem"), lang.keys)
        assertEquals("B", lang["shared.key"]!!.jsonPrimitive.content)
        assertTrue(plan.issues.none { it.path == "assets/minecraft/lang/en_us.json" && it.level != IssueLevel.NOTE })
    }

    @Test
    fun `atlas sources stack in pack order`() {
        val sources = plan().file("assets/minecraft/atlases/blocks.json")["sources"]!!.jsonArray
        assertEquals(listOf("a_items", "b_items"), sources.map { it.jsonObject["source"]!!.jsonPrimitive.content })
    }

    @Test
    fun `a file with one owner is taken from the later pack and said out loud`() {
        val plan = plan()
        val texture = plan.files.single { it.relative == "assets/minecraft/textures/item/paper.png" }
        assertTrue(String(texture.bytes).startsWith("plugin-b"))
        assertTrue(plan.issues.any { it.path == texture.relative && it.rule == MergeRule.SINGLE_OWNER_OVERWRITE && "plugin-b" in it.message })
    }

    @Test
    fun `two packs shipping the same file byte for byte is not a conflict`() {
        val plan = plan()
        assertNotNull(plan.files.single { it.relative == "assets/minecraft/models/item/stick.json" })
        assertTrue(plan.issues.none { it.path == "assets/minecraft/models/item/stick.json" })
    }

    @Test
    fun `the merged range is the highest floor and the lowest ceiling`() {
        val plan = plan()
        assertEquals("46", plan.range.first.toString())
        assertEquals("88", plan.range.second.toString())
        val meta = plan.file("pack.mcmeta")["pack"]!!.jsonObject
        assertEquals("plugin A items", meta["description"]!!.jsonPrimitive.content)
        assertEquals("46", meta["pack_format"]!!.jsonPrimitive.content)
        assertEquals("46", meta["min_format"]!!.jsonPrimitive.content)
        assertEquals("88", meta["max_format"]!!.jsonPrimitive.content)
        assertTrue(plan.issues.any { it.path == "pack.mcmeta" && "plugin-a" in it.message && "34" in it.message })
    }

    @Test
    fun `merges zip input packs and emits byte-reproducible zip output with stable sha1`(@TempDir dir: Path) {
        val dirA = PackSource(Path.of("src/test/resources/plugin-a"), 0)
        val dirB = PackSource(Path.of("src/test/resources/plugin-b"), 1)

        val zipA = dir.resolve("plugin-a.zip")
        val zipB = dir.resolve("plugin-b.zip")
        PackMerge(listOf(dirA), null, null).let { single ->
            single.writeZip(single.plan(), zipA)
        }
        PackMerge(listOf(dirB), null, null).let { single ->
            single.writeZip(single.plan(), zipB)
        }

        val fromZips = PackMerge(listOf(PackSource(zipA, 0), PackSource(zipB, 1)), null, null)
        val zipPlan = fromZips.plan()
        assertEquals(9, zipPlan.files.size)
        assertEquals(3, zipPlan.errors)

        val outZip1 = dir.resolve("merged-1.zip")
        val outZip2 = dir.resolve("merged-2.zip")
        val art1 = fromZips.writeZip(zipPlan, outZip1)
        val art2 = fromZips.writeZip(zipPlan, outZip2)
        assertEquals(art1.sha1, art2.sha1)
        assertEquals(40, art1.sha1.length)
        assertTrue(Files.readAllBytes(outZip1).contentEquals(Files.readAllBytes(outZip2)))

        val jsonText = JsonMergeReport.render(zipPlan, null, art1, dryRun = false)
        val parsed = JsonIo.parse(jsonText).jsonObject
        assertEquals(art1.sha1, parsed["zipSha1"]!!.jsonPrimitive.content)
        assertEquals("9", parsed["fileCount"]!!.jsonPrimitive.content)
        assertTrue(parsed["issues"]!!.jsonArray.all { it.jsonObject["rule"]!!.jsonPrimitive.content.startsWith("PM0") })
    }

    @Test
    fun `supports Minecraft 26_3 format 97 and minor-tuple range reconciliation`(@TempDir dir: Path) {
        assertEquals(97, PackFormats.formatOf("26.3"))
        assertEquals(PackFormat(46) to PackFormat(97), PackFormats.parseRange("1.21.4..26.3"))

        // [97, 2]..[97, 0] is rejected by PackMeta.build
        assertThrows(PackMetaException::class.java) {
            PackMeta.build("invalid minor", PackFormat(97, 2), PackFormat(97, 0))
        }

        val packA = writePack(
            dir.resolve("pack-26-a"),
            mapOf(
                "pack.mcmeta" to """{"pack": {"description": "26.3 base", "min_format": 97, "max_format": 97}}""",
            ),
        )
        val packB = writePack(
            dir.resolve("pack-26-b"),
            mapOf(
                "pack.mcmeta" to """{"pack": {"description": "26.3.1 patch", "min_format": [97, 1], "max_format": [97, 2]}}""",
            ),
        )
        val merged = PackMerge(listOf(PackSource(packA, 0), PackSource(packB, 1)), null, null).plan()
        assertEquals(PackFormat(97, 1), merged.range.first)
        assertEquals(PackFormat(97, 2), merged.range.second)

        val metaPack = merged.file("pack.mcmeta")["pack"]!!.jsonObject
        // Modern-only (65+): supported_formats and pack_format are omitted
        assertEquals(null, metaPack["supported_formats"])
        assertEquals(null, metaPack["pack_format"])
        assertEquals(listOf("97", "1"), metaPack["min_format"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("97", "2"), metaPack["max_format"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `unions select custom_model_data cases and deduplicates shadowed when keys`(@TempDir dir: Path) {
        val packA = writePack(
            dir.resolve("nexo-a"),
            mapOf(
                "pack.mcmeta" to """{"pack": {"description": "nexo a", "min_format": 84, "max_format": 97}}""",
                "assets/minecraft/items/diamond_sword.json" to """
                {
                  "model": {
                    "type": "minecraft:select",
                    "property": "minecraft:custom_model_data",
                    "cases": [
                      { "when": ["katana", "wakizashi"], "model": { "type": "minecraft:model", "model": "nexo:item/blade_a" } },
                      { "when": "rapier", "model": { "type": "minecraft:model", "model": "nexo:item/rapier" } }
                    ],
                    "fallback": { "type": "minecraft:model", "model": "minecraft:item/diamond_sword" }
                  }
                }
                """.trimIndent(),
            ),
        )
        val packB = writePack(
            dir.resolve("nexo-b"),
            mapOf(
                "pack.mcmeta" to """{"pack": {"description": "nexo b", "min_format": 84, "max_format": 97}}""",
                "assets/minecraft/items/diamond_sword.json" to """
                {
                  "oversized_in_gui": true,
                  "model": {
                    "type": "minecraft:select",
                    "property": "minecraft:custom_model_data",
                    "cases": [
                      { "when": "katana", "model": { "type": "minecraft:model", "model": "craft:item/katana_v2" } },
                      { "when": "halberd", "model": { "type": "minecraft:model", "model": "craft:item/halberd" } }
                    ],
                    "fallback": { "type": "minecraft:model", "model": "minecraft:item/diamond_sword" }
                  }
                }
                """.trimIndent(),
            ),
        )

        val merged = PackMerge(listOf(PackSource(packA, 0), PackSource(packB, 1)), null, null).plan()
        val swordRoot = merged.file("assets/minecraft/items/diamond_sword.json")
        assertTrue(swordRoot["oversized_in_gui"]!!.jsonPrimitive.boolean)

        val cases = swordRoot["model"]!!.jsonObject["cases"]!!.jsonArray
        // "katana" was pruned from packA's ["katana", "wakizashi"] so Minecraft's SelectItemModel codec sees no duplicate `when`
        val allWhenKeys = cases.flatMap { c ->
            val w = c.jsonObject["when"]!!
            if (w is JsonArray) w.map { it.jsonPrimitive.content } else listOf(w.jsonPrimitive.content)
        }
        assertEquals(listOf("wakizashi", "rapier", "katana", "halberd"), allWhenKeys)
        assertEquals(allWhenKeys.size, allWhenKeys.distinct().size)
        assertTrue(merged.issues.any { it.rule == MergeRule.ITEM_DEFINITION_COLLISION && "'katana'" in it.message })
    }

    @Test
    fun `chains select string dispatch into range_dispatch float fallback across mixed plugins`(@TempDir dir: Path) {
        val rangePack = writePack(
            dir.resolve("oraxen"),
            mapOf(
                "pack.mcmeta" to """{"pack": {"description": "oraxen", "min_format": 69, "max_format": 97}}""",
                "assets/minecraft/items/paper.json" to """
                {
                  "model": {
                    "type": "minecraft:range_dispatch",
                    "property": "minecraft:custom_model_data",
                    "entries": [
                      { "threshold": 1001, "model": { "type": "minecraft:model", "model": "oraxen:item/ruby" } }
                    ],
                    "fallback": { "type": "minecraft:model", "model": "minecraft:item/paper" }
                  }
                }
                """.trimIndent(),
            ),
        )
        val selectPack = writePack(
            dir.resolve("nexo"),
            mapOf(
                "pack.mcmeta" to """{"pack": {"description": "nexo", "min_format": 69, "max_format": 97}}""",
                "assets/minecraft/items/paper.json" to """
                {
                  "model": {
                    "type": "minecraft:select",
                    "property": "minecraft:custom_model_data",
                    "cases": [
                      { "when": "sapphire", "model": { "type": "minecraft:model", "model": "nexo:item/sapphire" } }
                    ],
                    "fallback": { "type": "minecraft:model", "model": "minecraft:item/paper" }
                  }
                }
                """.trimIndent(),
            ),
        )

        val merged = PackMerge(listOf(PackSource(rangePack, 0), PackSource(selectPack, 1)), null, null).plan()
        assertEquals(0, merged.errors)
        val rootModel = merged.file("assets/minecraft/items/paper.json")["model"]!!.jsonObject
        assertEquals("minecraft:select", rootModel["type"]!!.jsonPrimitive.content)
        assertEquals("sapphire", rootModel["cases"]!!.jsonArray.single().jsonObject["when"]!!.jsonPrimitive.content)

        val fallback = rootModel["fallback"]!!.jsonObject
        assertEquals("minecraft:range_dispatch", fallback["type"]!!.jsonPrimitive.content)
        assertEquals("1001", fallback["entries"]!!.jsonArray.single().jsonObject["threshold"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reconciles overlays entries in pack mcmeta and merges assets inside overlay directories`(@TempDir dir: Path) {
        val packA = writePack(
            dir.resolve("ov-a"),
            mapOf(
                "pack.mcmeta" to """
                {
                  "pack": { "description": "ov a", "min_format": 46, "max_format": 97, "pack_format": 46, "supported_formats": [46, 64] },
                  "overlays": {
                    "entries": [
                      { "directory": "v26_3", "min_format": 97, "max_format": 97 },
                      { "directory": "Bad/Name", "min_format": 97, "max_format": 97 }
                    ]
                  }
                }
                """.trimIndent(),
                "v26_3/assets/minecraft/lang/en_us.json" to """{"menu.a": "Alpha"}""",
            ),
        )
        val packB = writePack(
            dir.resolve("ov-b"),
            mapOf(
                "pack.mcmeta" to """
                {
                  "pack": { "description": "ov b", "min_format": 46, "max_format": 97, "pack_format": 46, "supported_formats": [46, 64] },
                  "overlays": {
                    "entries": [
                      { "directory": "v26_3", "min_format": 97, "max_format": [97, 2] }
                    ]
                  }
                }
                """.trimIndent(),
                "v26_3/assets/minecraft/lang/en_us.json" to """{"menu.b": "Beta"}""",
            ),
        )

        val merged = PackMerge(listOf(PackSource(packA, 0), PackSource(packB, 1)), null, null).plan()
        assertTrue(merged.issues.any { it.rule == MergeRule.INVALID_OVERLAY_DIRECTORY && "Bad/Name" in it.message })
        assertEquals(1, merged.overlays.size)
        assertEquals("v26_3", merged.overlays.single().directory)

        // Overlay lang files were key-unioned rather than overwritten as SINGLE_OWNER!
        val overlayLang = merged.file("v26_3/assets/minecraft/lang/en_us.json")
        assertEquals(setOf("menu.a", "menu.b"), overlayLang.keys)

        // Merged pack.mcmeta preserves overlays.entries
        val metaOverlays = merged.file("pack.mcmeta")["overlays"]!!.jsonObject["entries"]!!.jsonArray
        assertEquals(1, metaOverlays.size)
        assertEquals("v26_3", metaOverlays.single().jsonObject["directory"]!!.jsonPrimitive.content)
    }

    @Test
    fun `renders valid OASIS SARIF v2_1_0 report with rule descriptors and SRCROOT locations`() {
        val plan = plan()
        val sarifJson = JsonIo.parse(SarifReport.render(plan)).jsonObject
        assertEquals("2.1.0", sarifJson["version"]!!.jsonPrimitive.content)

        val run = sarifJson["runs"]!!.jsonArray.single().jsonObject
        val driver = run["tool"]!!.jsonObject["driver"]!!.jsonObject
        assertEquals("packmerge", driver["name"]!!.jsonPrimitive.content)
        assertEquals(MergeRule.entries.size, driver["rules"]!!.jsonArray.size)

        val results = run["results"]!!.jsonArray
        assertEquals(plan.issues.size, results.size)
        val firstLoc = results.first().jsonObject["locations"]!!.jsonArray.single()
            .jsonObject["physicalLocation"]!!.jsonObject["artifactLocation"]!!.jsonObject
        assertEquals("%SRCROOT%", firstLoc["uriBaseId"]!!.jsonPrimitive.content)
        assertTrue(results.any { it.jsonObject["ruleId"]!!.jsonPrimitive.content == "PM001" })
        assertTrue(results.any { it.jsonObject["ruleId"]!!.jsonPrimitive.content == "PM002" })
        assertTrue(results.any { it.jsonObject["ruleId"]!!.jsonPrimitive.content == "PM003" })
    }
}
