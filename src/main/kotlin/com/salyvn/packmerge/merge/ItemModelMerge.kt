package com.salyvn.packmerge.merge

import com.salyvn.packmerge.core.JsonIo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Mergers for legacy `models/item/<item>.json` (`overrides`) and 1.21.4..26.3 `items/<item>.json`
 * (`range_dispatch` on `custom_model_data` floats, `select` on `custom_model_data` strings, and hybrid
 * `select` -> `range_dispatch` fallback chains).
 */
object ItemModelMerge {

    /** A pre-1.21.4 model file. Mergeable when every pack's copy has an `overrides` list. */
    fun mergeLegacyModel(relative: String, sources: List<PackSource>, report: MergeReport): ByteArray? {
        val parsed = sources.mapNotNull { source -> read(relative, source, report)?.let { source to it } }
        if (parsed.size < 2) return null
        if (parsed.any { (_, root) -> root["overrides"] !is JsonArray }) {
            report.warn(
                relative,
                "Not every pack's copy has an overrides list, so there is no table to union here.",
                MergeRule.MODEL_PROPERTY_CONFLICT,
            )
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
                MergeRule.LEGACY_OVERRIDE_COLLISION,
            )
        }

        val bodies = parsed.map { (_, root) -> JsonObject(root.filterKeys { it != "overrides" }) }.distinct()
        if (bodies.size > 1) {
            report.warn(
                relative,
                "The packs disagree about the model itself, not just its overrides; ${parsed.last().first.name}'s parent and textures are the ones kept.",
                MergeRule.MODEL_PROPERTY_CONFLICT,
            )
        }

        report.note(
            relative,
            "${overrides.size} overrides unioned from ${parsed.size} packs.",
            MergeRule.ADDITIVE_MERGE_SUMMARY,
        )
        val base = parsed.last().second.toMutableMap()
        base["overrides"] = JsonArray(overrides)
        return JsonIo.render(JsonObject(base)).toByteArray()
    }

    /**
     * A 1.21.4..26.3 item model definition (`assets/<ns>/items/<item>.json`).
     * Supports `range_dispatch` on `custom_model_data`, `select` on `custom_model_data`, and hybrid
     * `select` + `range_dispatch` fallback chaining when one plugin uses string keys and another uses floats.
     */
    fun mergeDefinition(relative: String, sources: List<PackSource>, report: MergeReport): ByteArray? {
        val parsed = sources.mapNotNull { source -> read(relative, source, report)?.let { source to it } }
        if (parsed.size < 2) return null

        val rangePacks = mutableListOf<Pair<PackSource, JsonObject>>()
        val selectPacks = mutableListOf<Pair<PackSource, JsonObject>>()
        val unsupported = mutableListOf<PackSource>()

        parsed.forEach { (source, root) ->
            val model = root["model"] as? JsonObject
            when {
                isCustomModelDataRangeDispatch(model) -> rangePacks += source to root
                isCustomModelDataSelect(model) -> selectPacks += source to root
                else -> unsupported += source
            }
        }

        if (unsupported.isNotEmpty()) {
            report.error(
                relative,
                "${unsupported.joinToString(" and ") { it.name }} does not put a " +
                    "custom_model_data range_dispatch or select dispatch at the root, so the definitions cannot be unioned. " +
                    "${parsed.last().first.name}'s definition is the one written; merge this file by hand.",
                MergeRule.UNMERGEABLE_ASSET_JSON,
            )
            return null
        }

        val rangeModel = if (rangePacks.isNotEmpty()) {
            buildRangeDispatchModel(relative, rangePacks, report, checkFallbackConflict = selectPacks.isEmpty())
                ?: if (selectPacks.isEmpty()) return null else null
        } else {
            null
        }

        val selectModel = if (selectPacks.isNotEmpty()) {
            buildSelectModel(
                relative = relative,
                selectPacks = selectPacks,
                chainedFallback = rangeModel,
                report = report,
            ) ?: if (rangeModel == null) return null else null
        } else {
            null
        }

        val mergedModel = selectModel ?: rangeModel ?: return null
        if (selectModel != null && rangeModel != null) {
            report.note(
                relative,
                "Chained custom_model_data select (${selectPacks.joinToString(", ") { it.first.name }}) into range_dispatch fallback (${rangePacks.joinToString(", ") { it.first.name }}).",
                MergeRule.ADDITIVE_MERGE_SUMMARY,
            )
        }

        val root = reconcileRootAttributes(relative, parsed, mergedModel, report)
        return JsonIo.render(root).toByteArray()
    }

    private fun buildRangeDispatchModel(
        relative: String,
        rangePacks: List<Pair<PackSource, JsonObject>>,
        report: MergeReport,
        checkFallbackConflict: Boolean,
    ): JsonObject? {
        val entries = linkedMapOf<Double, Pair<String, JsonElement>>()
        rangePacks.forEach { (source, root) ->
            val model = root["model"] as JsonObject
            (model["entries"] as? JsonArray)?.forEach { entry ->
                val threshold = ((entry as? JsonObject)?.get("threshold") as? JsonPrimitive)?.content?.toDoubleOrNull()
                if (threshold == null) {
                    report.error(
                        relative,
                        "${source.name} has an entry with no readable threshold; it was dropped.",
                        MergeRule.UNMERGEABLE_ASSET_JSON,
                    )
                    return@forEach
                }
                val previous = entries.put(threshold, source.name to entry)
                if (previous != null && previous.second != entry) {
                    report.error(
                        relative,
                        "custom_model_data ${format(threshold)} is claimed by both ${previous.first} and ${source.name}. " +
                            "Only ${source.name}'s model renders; the other item is invisible.",
                        MergeRule.ITEM_DEFINITION_COLLISION,
                    )
                }
            }
        }
        if (entries.isEmpty()) return null

        val lastModel = rangePacks.last().second["model"] as JsonObject
        val fieldsToCheck = if (checkFallbackConflict) listOf("scale", "index", "fallback") else listOf("scale", "index")
        fieldsToCheck.forEach { field ->
            val values = rangePacks.mapNotNull { (source, root) -> (root["model"] as JsonObject)[field]?.let { source.name to it } }
            if (values.map { it.second }.distinct().size > 1) {
                report.warn(
                    relative,
                    "The packs disagree about $field on the dispatch; ${rangePacks.last().first.name}'s value is the one kept.",
                    MergeRule.MODEL_PROPERTY_CONFLICT,
                )
            }
        }

        val model = lastModel.toMutableMap()
        model["entries"] = JsonArray(entries.entries.sortedBy { it.key }.map { it.value.second })
        if (rangePacks.size > 1) {
            report.note(
                relative,
                "${entries.size} custom_model_data entries unioned from ${rangePacks.size} packs.",
                MergeRule.ADDITIVE_MERGE_SUMMARY,
            )
        }
        return JsonObject(model)
    }

    private data class SelectCaseSpec(
        val pack: String,
        val keys: MutableList<String>,
        val wasArray: Boolean,
        val model: JsonElement,
        val rawExtra: Map<String, JsonElement>,
    )

    private fun buildSelectModel(
        relative: String,
        selectPacks: List<Pair<PackSource, JsonObject>>,
        chainedFallback: JsonObject?,
        report: MergeReport,
    ): JsonObject? {
        val collectedCases = mutableListOf<SelectCaseSpec>()
        val keyOwner = linkedMapOf<String, Pair<String, JsonElement>>()

        selectPacks.forEach { (source, root) ->
            val model = root["model"] as JsonObject
            (model["cases"] as? JsonArray)?.forEach { rawCase ->
                val caseObj = rawCase as? JsonObject
                val whenEl = caseObj?.get("when")
                val targetModel = caseObj?.get("model")
                if (caseObj == null || whenEl == null || targetModel == null) {
                    report.error(
                        relative,
                        "${source.name} has a select case without readable 'when' and 'model' fields; it was dropped.",
                        MergeRule.UNMERGEABLE_ASSET_JSON,
                    )
                    return@forEach
                }
                val keys = extractWhenStrings(whenEl)
                if (keys.isEmpty()) {
                    report.error(
                        relative,
                        "${source.name} has a select case with no string values in 'when'; it was dropped.",
                        MergeRule.UNMERGEABLE_ASSET_JSON,
                    )
                    return@forEach
                }

                for (key in keys) {
                    val prev = keyOwner[key]
                    if (prev != null && prev.first != source.name && prev.second != targetModel) {
                        report.error(
                            relative,
                            "custom_model_data '$key' is claimed by both ${prev.first} and ${source.name}. " +
                                "Only ${source.name}'s model renders; the other item is invisible.",
                            MergeRule.ITEM_DEFINITION_COLLISION,
                        )
                    }
                    // Prune shadowed key from any earlier case so Minecraft's SelectItemModel codec never fails on duplicate `when`.
                    if (prev != null) {
                        collectedCases.forEach { existing -> existing.keys.remove(key) }
                    }
                    keyOwner[key] = source.name to targetModel
                }

                collectedCases += SelectCaseSpec(
                    pack = source.name,
                    keys = keys.distinct().toMutableList(),
                    wasArray = whenEl is JsonArray,
                    model = targetModel,
                    rawExtra = caseObj.filterKeys { it != "when" && it != "model" },
                )
            }
        }

        val activeCases = collectedCases.filter { it.keys.isNotEmpty() }
        if (activeCases.isEmpty()) return null

        val fieldsToCheck = if (chainedFallback == null) listOf("index", "fallback") else listOf("index")
        fieldsToCheck.forEach { field ->
            val values = selectPacks.mapNotNull { (source, root) -> (root["model"] as JsonObject)[field]?.let { source.name to it } }
            if (values.map { it.second }.distinct().size > 1) {
                report.warn(
                    relative,
                    "The packs disagree about $field on the select dispatch; ${selectPacks.last().first.name}'s value is the one kept.",
                    MergeRule.MODEL_PROPERTY_CONFLICT,
                )
            }
        }

        val serializedCases = activeCases.map { spec ->
            val whenJson: JsonElement = if (!spec.wasArray && spec.keys.size == 1) {
                JsonPrimitive(spec.keys.single())
            } else {
                JsonArray(spec.keys.map { JsonPrimitive(it) })
            }
            val map = linkedMapOf<String, JsonElement>()
            map["when"] = whenJson
            map["model"] = spec.model
            map.putAll(spec.rawExtra)
            JsonObject(map)
        }

        val lastModel = selectPacks.last().second["model"] as JsonObject
        val model = lastModel.toMutableMap()
        model["cases"] = JsonArray(serializedCases)
        if (chainedFallback != null) {
            model["fallback"] = chainedFallback
        }
        if (selectPacks.size > 1) {
            report.note(
                relative,
                "${keyOwner.size} custom_model_data select case(s) unioned from ${selectPacks.size} packs.",
                MergeRule.ADDITIVE_MERGE_SUMMARY,
            )
        }
        return JsonObject(model)
    }

    private fun reconcileRootAttributes(
        relative: String,
        parsed: List<Pair<PackSource, JsonObject>>,
        mergedModel: JsonObject,
        report: MergeReport,
    ): JsonObject {
        val root = parsed.last().second.toMutableMap()
        root["model"] = mergedModel

        // Reconcile 1.21.4+ `hand_animation_on_swap` and 1.21.6+/26.3 `oversized_in_gui` root flags.
        val handAnim = parsed.mapNotNull { (source, obj) ->
            (obj["hand_animation_on_swap"] as? JsonPrimitive)?.booleanOrNull?.let { source.name to it }
        }
        if (handAnim.map { it.second }.distinct().size > 1) {
            report.warn(
                relative,
                "The packs disagree about hand_animation_on_swap; ${handAnim.last().first}'s value (${handAnim.last().second}) is kept.",
                MergeRule.MODEL_PROPERTY_CONFLICT,
            )
            root["hand_animation_on_swap"] = JsonPrimitive(handAnim.last().second)
        } else if (handAnim.isNotEmpty()) {
            root["hand_animation_on_swap"] = JsonPrimitive(handAnim.last().second)
        }

        val oversized = parsed.mapNotNull { (source, obj) ->
            (obj["oversized_in_gui"] as? JsonPrimitive)?.booleanOrNull?.let { source.name to it }
        }
        if (oversized.map { it.second }.distinct().size > 1) {
            report.warn(
                relative,
                "The packs disagree about oversized_in_gui; enabling oversized_in_gui=true so oversized custom models are not clipped in GUI slots.",
                MergeRule.MODEL_PROPERTY_CONFLICT,
            )
            root["oversized_in_gui"] = JsonPrimitive(true)
        } else if (oversized.any { it.second }) {
            root["oversized_in_gui"] = JsonPrimitive(true)
        }

        return JsonObject(root)
    }

    private fun extractWhenStrings(element: JsonElement): List<String> = when (element) {
        is JsonPrimitive -> if (element.isString) listOf(element.content) else emptyList()
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        else -> emptyList()
    }

    private fun isCustomModelDataRangeDispatch(model: JsonObject?): Boolean {
        val type = (model?.get("type") as? JsonPrimitive)?.content?.removePrefix("minecraft:")
        val property = (model?.get("property") as? JsonPrimitive)?.content?.removePrefix("minecraft:")
        return type == "range_dispatch" && property == "custom_model_data"
    }

    private fun isCustomModelDataSelect(model: JsonObject?): Boolean {
        val type = (model?.get("type") as? JsonPrimitive)?.content?.removePrefix("minecraft:")
        val property = (model?.get("property") as? JsonPrimitive)?.content?.removePrefix("minecraft:")
        return type == "select" && property == "custom_model_data"
    }

    private fun read(relative: String, source: PackSource, report: MergeReport): JsonObject? = try {
        JsonIo.parse(source.readText(relative)).jsonObject
    } catch (failure: Exception) {
        report.error(
            relative,
            "${source.name}'s copy is not a readable JSON object (${failure.message}); it was left out of the merge.",
            MergeRule.UNMERGEABLE_ASSET_JSON,
        )
        null
    }

    private fun describe(predicate: JsonObject): String =
        predicate.entries.sortedBy { it.key }.joinToString(", ") { (key, value) -> "$key=${(value as? JsonPrimitive)?.content ?: value}" }

    private fun format(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
