package com.salyvn.packmerge.merge

import com.salyvn.packmerge.core.JsonIo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The files two custom-item plugins fight over.
 *
 * Both of them hang their items off the same vanilla item, so both write the same file: before 1.21.4 it was
 * `models/item/paper.json` with an `overrides` list, and from 1.21.4 it is `items/paper.json` with a
 * `range_dispatch` on `minecraft:custom_model_data`. Neither file has a single owner in any meaningful
 * sense — each pack contributes rows to a table — and copying one over the other deletes every row the
 * other pack contributed. That is the most expensive thing a hand merge does, because nothing errors: the
 * items still exist in the plugin, still have their custom model data, and render as plain paper.
 *
 * Rows are therefore unioned. Where two packs claim the same custom model data value on the same item, only
 * one of them can ever render, and that is reported per value: fixing it means renumbering items in a
 * plugin's configuration and in every chest that already holds one.
 */
object ItemModelMerge {

    /** A pre-1.21.4 model file. Mergeable when every pack's copy has an `overrides` list. */
    fun mergeLegacyModel(relative: String, sources: List<PackSource>, report: MergeReport): ByteArray? {
        val parsed = sources.mapNotNull { source -> read(relative, source, report)?.let { source to it } }
        if (parsed.size < 2) return null
        if (parsed.any { (_, root) -> root["overrides"] !is JsonArray }) {
            report.warn(relative, "Not every pack's copy has an overrides list, so there is no table to union here.")
            return null
        }

        val overrides = mutableListOf<JsonElement>()
        val claims = linkedMapOf<String, MutableList<Pair<String, String>>>()
        parsed.forEach { (source, root) ->
            (root["overrides"] as JsonArray).forEach { entry ->
                overrides += entry
                val override = entry as? JsonObject ?: return@forEach
                val predicate = override["predicate"] as? JsonObject ?: return@forEach
                val model = (override["model"] as? JsonPrimitive)?.content ?: "?"
                claims.getOrPut(describe(predicate)) { mutableListOf() } += source.name to model
            }
        }

        claims.filterValues { it.map { (pack, _) -> pack }.distinct().size > 1 }.forEach { (predicate, holders) ->
            val winner = holders.last()
            report.error(
                relative,
                "$predicate is claimed by ${holders.joinToString(" and ") { (pack, model) -> "$pack ($model)" }}. " +
                    "Only ${winner.second} renders, because the last matching override wins; the other item is invisible.",
            )
        }

        // The model's own body — parent, textures, display — should be the same file from vanilla in every
        // pack. When it is not, one version has to be chosen, and the later pack is the one that wins.
        val bodies = parsed.map { (_, root) -> JsonObject(root.filterKeys { it != "overrides" }) }.distinct()
        if (bodies.size > 1) {
            report.warn(relative, "The packs disagree about the model itself, not just its overrides; ${parsed.last().first.name}'s parent and textures are the ones kept.")
        }

        report.note(relative, "${overrides.size} overrides unioned from ${parsed.size} packs.")
        val base = parsed.last().second.toMutableMap()
        base["overrides"] = JsonArray(overrides)
        return JsonIo.render(JsonObject(base)).toByteArray()
    }

    /** An item model definition. Mergeable when every pack's root is a `custom_model_data` dispatch. */
    fun mergeDefinition(relative: String, sources: List<PackSource>, report: MergeReport): ByteArray? {
        val parsed = sources.mapNotNull { source -> read(relative, source, report)?.let { source to it } }
        if (parsed.size < 2) return null

        val dispatches = parsed.map { (source, root) -> source to (root["model"] as? JsonObject) }
        val notDispatch = dispatches.filter { (_, model) -> !isCustomModelDataDispatch(model) }
        if (notDispatch.isNotEmpty()) {
            report.error(
                relative,
                "${notDispatch.joinToString(" and ") { (source, _) -> source.name }} does not put a " +
                    "custom_model_data range_dispatch at the root, so the two definitions cannot be unioned. " +
                    "${parsed.last().first.name}'s definition is the one written; merge this file by hand.",
            )
            return null
        }

        val entries = linkedMapOf<Double, Pair<String, JsonElement>>()
        parsed.forEach { (source, root) ->
            val model = root["model"] as JsonObject
            (model["entries"] as? JsonArray)?.forEach { entry ->
                val threshold = ((entry as? JsonObject)?.get("threshold") as? JsonPrimitive)?.content?.toDoubleOrNull()
                if (threshold == null) {
                    report.error(relative, "${source.name} has an entry with no readable threshold; it was dropped.")
                    return@forEach
                }
                val previous = entries.put(threshold, source.name to entry)
                if (previous != null && previous.second != entry) {
                    report.error(
                        relative,
                        "custom_model_data ${format(threshold)} is claimed by both ${previous.first} and ${source.name}. " +
                            "Only ${source.name}'s model renders; the other item is invisible.",
                    )
                }
            }
        }
        if (entries.isEmpty()) return null

        val last = parsed.last().second
        val lastModel = last["model"] as JsonObject
        listOf("scale", "index", "fallback").forEach { field ->
            val values = parsed.mapNotNull { (source, root) -> (root["model"] as JsonObject)[field]?.let { source.name to it } }
            if (values.map { it.second }.distinct().size > 1) {
                report.warn(relative, "The packs disagree about $field on the dispatch; ${parsed.last().first.name}'s value is the one kept.")
            }
        }

        val model = lastModel.toMutableMap()
        model["entries"] = JsonArray(entries.entries.sortedBy { it.key }.map { it.value.second })
        val root = last.toMutableMap()
        root["model"] = JsonObject(model)
        report.note(relative, "${entries.size} custom_model_data entries unioned from ${parsed.size} packs.")
        return JsonIo.render(JsonObject(root)).toByteArray()
    }

    private fun isCustomModelDataDispatch(model: JsonObject?): Boolean {
        val type = (model?.get("type") as? JsonPrimitive)?.content?.removePrefix("minecraft:")
        val property = (model?.get("property") as? JsonPrimitive)?.content?.removePrefix("minecraft:")
        return type == "range_dispatch" && property == "custom_model_data"
    }

    private fun read(relative: String, source: PackSource, report: MergeReport): JsonObject? = try {
        JsonIo.parse(source.readText(relative)).jsonObject
    } catch (failure: Exception) {
        report.error(relative, "${source.name}'s copy is not a readable JSON object (${failure.message}); it was left out of the merge.")
        null
    }

    /** `custom_model_data=3, pulling=1` — a predicate map in a form two packs can be compared on. */
    private fun describe(predicate: JsonObject): String =
        predicate.entries.sortedBy { it.key }.joinToString(", ") { (key, value) -> "$key=${(value as? JsonPrimitive)?.content ?: value}" }

    private fun format(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
