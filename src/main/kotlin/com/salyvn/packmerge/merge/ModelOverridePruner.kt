package com.salyvn.packmerge.merge

import com.salyvn.packmerge.core.JsonIo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

data class PruneResult(
    val files: List<MergedFile>,
    val prunedEntries: Int,
)

/**
 * Semantics-preserving override & dispatch tree optimizer (`--prune-overrides`, rule `PM015`):
 * - Legacy `models/**/*.json`: removes unreachable overrides shadowed by later dominating predicates,
 *   redundant step-function knots on single numeric predicates, and leading no-op self-model overrides.
 * - Modern `items/**/*.json` (`1.21.4..26.3`): eliminates redundant `range_dispatch` step knots, drops
 *   `select` cases equivalent to `fallback`, coalesces `select` cases sharing identical target models, and
 *   folds tautological `condition` nodes where `on_true == on_false`.
 */
object ModelOverridePruner {

    fun pruneAll(
        files: List<MergedFile>,
        overlayDirs: Set<String>,
        report: MergeReport,
    ): PruneResult {
        var totalPruned = 0
        val out = files.map { file ->
            when (file.kind) {
                MergeKind.LEGACY_MODEL -> {
                    val (updated, count) = pruneLegacyModel(file.relative, file.bytes, overlayDirs, report)
                    totalPruned += count
                    if (updated != null) MergedFile(file.relative, updated, file.from, file.kind, file.merged) else file
                }
                MergeKind.ITEM_DEFINITION -> {
                    val (updated, count) = pruneItemDefinition(file.relative, file.bytes, report)
                    totalPruned += count
                    if (updated != null) MergedFile(file.relative, updated, file.from, file.kind, file.merged) else file
                }
                else -> file
            }
        }
        return PruneResult(out, totalPruned)
    }

    fun pruneLegacyModel(
        relative: String,
        bytes: ByteArray,
        overlayDirs: Set<String>,
        report: MergeReport,
    ): Pair<ByteArray?, Int> {
        val root = try {
            JsonIo.parse(String(bytes, Charsets.UTF_8)).jsonObject
        } catch (_: Exception) {
            return null to 0
        }
        val overrides = root["overrides"] as? JsonArray ?: return null to 0
        if (overrides.isEmpty()) return null to 0

        val parsedSpecs = overrides.map { el -> parseLegacyOverride(el) }
        val keptIndices = mutableListOf<Int>()
        var shadowedCount = 0

        // Pass 1: In Minecraft's sequential last-match-wins evaluation, override i is 100% unreachable if
        // any later override j > i has predicates P_j that dominate P_i (keys(P_j) subseteq keys(P_i) and P_j[k] <= P_i[k]).
        for (i in parsedSpecs.indices) {
            val specI = parsedSpecs[i]
            if (specI == null) {
                keptIndices += i
                continue
            }
            val dominatedByLater = (i + 1 until parsedSpecs.size).any { j ->
                val specJ = parsedSpecs[j]
                specJ != null && dominates(dominator = specJ, candidate = specI)
            }
            if (dominatedByLater) {
                shadowedCount++
            } else {
                keptIndices += i
            }
        }

        // Pass 2: Prune consecutive redundant step-function knots on identical single numeric predicate keys
        // (e.g. custom_model_data=10 -> modelA followed immediately by custom_model_data=11 -> modelA),
        // plus leading no-op self-overrides pointing to the parent item model itself.
        val selfModelId = canonicalModelId(relative, overlayDirs)
        val finalElements = mutableListOf<JsonElement>()
        val finalSpecs = mutableListOf<LegacySpec?>()
        var knotOrSelfCount = 0

        for (idx in keptIndices) {
            val el = overrides[idx]
            val spec = parsedSpecs[idx]
            if (finalElements.isEmpty() && spec != null && selfModelId != null && normalizeId(spec.model) == selfModelId) {
                knotOrSelfCount++
                continue
            }
            val prev = finalSpecs.lastOrNull()
            if (prev != null && spec != null && isRedundantSinglePredicateKnot(prev, spec)) {
                knotOrSelfCount++
                continue
            }
            finalElements += el
            finalSpecs += spec
        }

        val pruned = shadowedCount + knotOrSelfCount
        if (pruned == 0) return null to 0

        val updatedRoot = root.toMutableMap()
        if (finalElements.isEmpty()) {
            updatedRoot.remove("overrides")
        } else {
            updatedRoot["overrides"] = JsonArray(finalElements)
        }
        report.note(
            relative,
            "Pruned $pruned redundant/shadowed legacy override(s) ($shadowedCount shadowed by later predicates, $knotOrSelfCount no-op knot/self entries); ${finalElements.size} active override(s) remain.",
            MergeRule.MODEL_OVERRIDE_PRUNED,
        )
        return JsonIo.render(JsonObject(updatedRoot)).toByteArray() to pruned
    }

    fun pruneItemDefinition(
        relative: String,
        bytes: ByteArray,
        report: MergeReport,
    ): Pair<ByteArray?, Int> {
        val root = try {
            JsonIo.parse(String(bytes, Charsets.UTF_8)).jsonObject
        } catch (_: Exception) {
            return null to 0
        }
        val modelObj = root["model"] as? JsonObject ?: return null to 0
        val counter = IntCounter()
        val optimizedModel = pruneModelNode(modelObj, counter)
        if (counter.value == 0 || optimizedModel == modelObj) return null to 0

        val updatedRoot = root.toMutableMap()
        updatedRoot["model"] = optimizedModel
        report.note(
            relative,
            "Pruned ${counter.value} redundant item dispatch branch(es)/knot(s) in modern item definition tree.",
            MergeRule.MODEL_OVERRIDE_PRUNED,
        )
        return JsonIo.render(JsonObject(updatedRoot)).toByteArray() to counter.value
    }

    private class IntCounter(var value: Int = 0)

    private fun pruneModelNode(node: JsonObject, counter: IntCounter): JsonObject {
        val rawType = (node["type"] as? JsonPrimitive)?.content?.removePrefix("minecraft:") ?: return node
        return when (rawType) {
            "condition" -> pruneConditionNode(node, counter)
            "range_dispatch" -> pruneRangeDispatchNode(node, counter)
            "select" -> pruneSelectNode(node, counter)
            "composite" -> pruneCompositeNode(node, counter)
            else -> node
        }
    }

    private fun pruneConditionNode(node: JsonObject, counter: IntCounter): JsonObject {
        val onTrue = (node["on_true"] as? JsonObject)?.let { pruneModelNode(it, counter) }
        val onFalse = (node["on_false"] as? JsonObject)?.let { pruneModelNode(it, counter) }
        if (onTrue != null && onTrue == onFalse) {
            counter.value++
            return onTrue
        }
        val map = node.toMutableMap()
        if (onTrue != null) map["on_true"] = onTrue
        if (onFalse != null) map["on_false"] = onFalse
        return JsonObject(map)
    }

    private fun pruneRangeDispatchNode(node: JsonObject, counter: IntCounter): JsonObject {
        val fallback = (node["fallback"] as? JsonObject)?.let { pruneModelNode(it, counter) }
        val rawEntries = node["entries"] as? JsonArray ?: return node

        data class RangeRow(val threshold: Double, val model: JsonObject, val rawEntry: JsonObject)
        val byThreshold = linkedMapOf<Double, RangeRow>()
        val unparsed = mutableListOf<JsonElement>()

        for (el in rawEntries) {
            val obj = el as? JsonObject
            val thresh = (obj?.get("threshold") as? JsonPrimitive)?.content?.toDoubleOrNull()
            val entryModel = (obj?.get("model") as? JsonObject)?.let { pruneModelNode(it, counter) }
            if (obj == null || thresh == null || entryModel == null) {
                unparsed += el
                continue
            }
            val updatedEntry = JsonObject(obj.toMutableMap().apply { put("model", entryModel) })
            if (byThreshold.put(thresh, RangeRow(thresh, entryModel, updatedEntry)) != null) {
                counter.value++
            }
        }

        if (unparsed.isNotEmpty()) return node

        val sorted = byThreshold.values.sortedBy { it.threshold }
        val kept = mutableListOf<JsonElement>()
        var activeModel: JsonObject? = fallback

        for (row in sorted) {
            val extraKeys = row.rawEntry.keys - setOf("threshold", "model")
            if (extraKeys.isEmpty() && activeModel != null && row.model == activeModel) {
                counter.value++
            } else {
                kept += row.rawEntry
                activeModel = row.model
            }
        }

        if (kept.isEmpty() && fallback != null) {
            counter.value++
            return fallback
        }

        val map = node.toMutableMap()
        map["entries"] = JsonArray(kept)
        if (fallback != null) map["fallback"] = fallback
        return JsonObject(map)
    }

    private fun pruneSelectNode(node: JsonObject, counter: IntCounter): JsonObject {
        val fallback = (node["fallback"] as? JsonObject)?.let { pruneModelNode(it, counter) }
        val rawCases = node["cases"] as? JsonArray ?: return node

        data class CoalescedCase(
            val keys: MutableList<String>,
            var preferArray: Boolean,
            val model: JsonObject,
            val extra: Map<String, JsonElement>,
        )

        val coalesced = mutableListOf<CoalescedCase>()
        val seenKeys = linkedMapOf<String, Int>()

        for (el in rawCases) {
            val obj = el as? JsonObject ?: return node
            val whenEl = obj["when"] ?: return node
            val caseModel = (obj["model"] as? JsonObject)?.let { pruneModelNode(it, counter) } ?: return node
            val keys = extractCaseKeys(whenEl)
            if (keys.isEmpty()) return node
            val extra = obj.filterKeys { it != "when" && it != "model" }

            // Shadowed earlier keys in the same select table are removed (keeping last).
            for (k in keys) {
                val prevIdx = seenKeys[k]
                if (prevIdx != null) {
                    coalesced[prevIdx].keys.remove(k)
                    counter.value++
                }
            }

            if (extra.isEmpty() && fallback != null && caseModel == fallback) {
                counter.value++
                continue
            }

            val existing = coalesced.firstOrNull { it.model == caseModel && it.extra == extra && it.keys.isNotEmpty() }
            if (existing != null) {
                val added = keys.filterNot { it in existing.keys }
                existing.keys.addAll(added)
                existing.preferArray = true
                counter.value++
                val idx = coalesced.indexOf(existing)
                keys.forEach { seenKeys[it] = idx }
            } else {
                val newIdx = coalesced.size
                val uniqueKeys = keys.distinct().toMutableList()
                coalesced += CoalescedCase(
                    keys = uniqueKeys,
                    preferArray = whenEl is JsonArray,
                    model = caseModel,
                    extra = extra,
                )
                uniqueKeys.forEach { seenKeys[it] = newIdx }
            }
        }

        val activeCases = coalesced.filter { it.keys.isNotEmpty() }
        if (activeCases.isEmpty() && fallback != null) {
            counter.value++
            return fallback
        }

        val serialized = activeCases.map { c ->
            val whenOut: JsonElement = if (!c.preferArray && c.keys.size == 1) {
                JsonPrimitive(c.keys.single())
            } else {
                JsonArray(c.keys.map { JsonPrimitive(it) })
            }
            val m = linkedMapOf<String, JsonElement>()
            m["when"] = whenOut
            m["model"] = c.model
            m.putAll(c.extra)
            JsonObject(m)
        }

        val map = node.toMutableMap()
        map["cases"] = JsonArray(serialized)
        if (fallback != null) map["fallback"] = fallback
        return JsonObject(map)
    }

    private fun pruneCompositeNode(node: JsonObject, counter: IntCounter): JsonObject {
        val models = node["models"] as? JsonArray ?: return node
        val updated = models.map { el -> (el as? JsonObject)?.let { pruneModelNode(it, counter) } ?: el }
        val map = node.toMutableMap()
        map["models"] = JsonArray(updated)
        return JsonObject(map)
    }

    private data class PredValue(val numeric: Double?, val raw: String)

    private data class LegacySpec(
        val predicates: Map<String, PredValue>,
        val model: String,
        val extraKeys: Set<String>,
    )

    private fun parseLegacyOverride(element: JsonElement): LegacySpec? {
        val obj = element as? JsonObject ?: return null
        val predObj = obj["predicate"] as? JsonObject ?: return null
        val model = (obj["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (predObj.isEmpty()) return null
        val preds = linkedMapOf<String, PredValue>()
        for ((k, v) in predObj) {
            val prim = v as? JsonPrimitive ?: return null
            val num = if (!prim.isString) prim.content.toDoubleOrNull() else null
            preds[k.removePrefix("minecraft:")] = PredValue(num, prim.content)
        }
        return LegacySpec(
            predicates = preds,
            model = model,
            extraKeys = obj.keys - setOf("predicate", "model"),
        )
    }

    /**
     * `dominator` (at index `j > i`) shadows `candidate` (at index `i`) iff every predicate required by
     * `dominator` is also required by `candidate` with `dominator.threshold <= candidate.threshold`.
     */
    private fun dominates(dominator: LegacySpec, candidate: LegacySpec): Boolean {
        if (dominator.extraKeys.isNotEmpty() || candidate.extraKeys.isNotEmpty()) return false
        for ((key, domVal) in dominator.predicates) {
            val candVal = candidate.predicates[key] ?: return false
            if (domVal.numeric != null && candVal.numeric != null) {
                if (domVal.numeric > candVal.numeric) return false
            } else if (domVal.raw != candVal.raw) {
                return false
            }
        }
        return true
    }

    private fun isRedundantSinglePredicateKnot(prev: LegacySpec, next: LegacySpec): Boolean {
        if (prev.extraKeys.isNotEmpty() || next.extraKeys.isNotEmpty()) return false
        if (prev.predicates.size != 1 || next.predicates.size != 1) return false
        val (k1, v1) = prev.predicates.entries.single()
        val (k2, v2) = next.predicates.entries.single()
        if (k1 != k2 || v1.numeric == null || v2.numeric == null) return false
        return v2.numeric >= v1.numeric && normalizeId(prev.model) == normalizeId(next.model)
    }

    private fun canonicalModelId(relative: String, overlayDirs: Set<String>): String? {
        val raw = relative.split('/')
        val parts = if (raw.size >= 5 && raw[0] in overlayDirs && raw[1] == "assets") raw.drop(1) else raw
        if (parts.size < 4 || parts[0] != "assets" || parts[2] != "models" || !relative.endsWith(".json")) return null
        val ns = parts[1]
        val subPath = parts.drop(3).joinToString("/").removeSuffix(".json")
        return "$ns:$subPath"
    }

    private fun normalizeId(id: String): String = if (':' in id) id else "minecraft:$id"

    private fun extractCaseKeys(element: JsonElement): List<String> = when (element) {
        is JsonPrimitive -> if (element.isString) listOf(element.content) else emptyList()
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        else -> emptyList()
    }
}
