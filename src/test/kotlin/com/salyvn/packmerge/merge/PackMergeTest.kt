package com.salyvn.packmerge.merge

import com.salyvn.packmerge.core.JsonIo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The two fixture packs stand in for two custom-item plugins that were each told they own
 * `minecraft:item/paper`, `minecraft:item/bow` and the default font. Everything the merge has to get right
 * collides in them at least once.
 */
class PackMergeTest {

    private fun plan(): MergePlan {
        val a = PackSource(Path.of("src/test/resources/plugin-a"), 0)
        val b = PackSource(Path.of("src/test/resources/plugin-b"), 1)
        return PackMerge(listOf(a, b), null, null).plan()
    }

    private fun MergePlan.file(relative: String): JsonObject =
        JsonIo.parse(String(files.single { it.relative == relative }.bytes)).jsonObject

    @Test
    fun `overrides from both packs end up in one list`() {
        val plan = plan()
        val overrides = plan.file("assets/minecraft/models/item/paper.json")["overrides"] as JsonArray
        assertEquals(4, overrides.size)
        assertEquals(
            listOf("a:item/scroll", "a:item/note", "b:item/map", "b:item/gem"),
            overrides.map { it.jsonObject["model"]!!.jsonPrimitive.content },
        )
        // The body of the model is kept, not just the table.
        assertEquals("minecraft:item/generated", plan.file("assets/minecraft/models/item/paper.json")["parent"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a custom model data value two packs claim is reported as unreachable`() {
        val errors = plan().issues.filter { it.level == IssueLevel.ERROR }.map { it.message }
        assertTrue(errors.any { "custom_model_data=2" in it && "plugin-a" in it && "plugin-b" in it }, errors.toString())
    }

    @Test
    fun `item model definitions are unioned by threshold and sorted`() {
        val model = plan().file("assets/minecraft/items/bow.json")["model"]!!.jsonObject
        assertEquals("minecraft:custom_model_data", model["property"]!!.jsonPrimitive.content)
        val entries = model["entries"]!!.jsonArray
        assertEquals(listOf("1", "7"), entries.map { it.jsonObject["threshold"]!!.jsonPrimitive.content })
        // Threshold 1 is claimed twice, and the later pack is the one that renders.
        assertEquals("b:item/warbow", entries.first().jsonObject["model"]!!.jsonObject["model"]!!.jsonPrimitive.content)
        assertTrue(plan().issues.any { it.level == IssueLevel.ERROR && "custom_model_data 1" in it.message })
    }

    @Test
    fun `font providers are concatenated and the shared codepoint is an error`() {
        val plan = plan()
        val providers = plan.file("assets/minecraft/font/default.json")["providers"]!!.jsonArray
        assertEquals(5, providers.size)
        assertEquals("space", providers.first().jsonObject["type"]!!.jsonPrimitive.content)
        val issues = plan.issues.filter { it.path == "assets/minecraft/font/default.json" }
        assertTrue(issues.any { it.level == IssueLevel.ERROR && "U+F010" in it.message }, issues.toString())
        // U+F001 and U+F020 belong to one pack each, so they are not reported.
        assertTrue(issues.none { "U+F001" in it.message || "U+F020" in it.message })
        // The reference provider cannot be checked, and the report says so rather than implying it was.
        assertTrue(issues.any { it.level == IssueLevel.NOTE && "reference, ttf or unihex" in it.message })
    }

    @Test
    fun `sounds and translations are key unions, and only the sound clash is a warning`() {
        val plan = plan()
        val sounds = plan.file("assets/minecraft/sounds.json")
        assertEquals(setOf("a.click", "shared.hit", "b.pop"), sounds.keys)
        assertTrue(plan.issues.any { it.path == "assets/minecraft/sounds.json" && it.level == IssueLevel.WARNING && "shared.hit" in it.message })

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
        assertTrue(plan.issues.any { it.path == texture.relative && it.level == IssueLevel.WARNING && "plugin-b" in it.message })
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
        // plugin-a claimed from 34 and no longer does, which the report explains rather than hiding.
        assertTrue(plan.issues.any { it.path == "pack.mcmeta" && "plugin-a" in it.message && "34" in it.message })
    }
}
