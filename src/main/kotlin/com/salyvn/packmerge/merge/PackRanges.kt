package com.salyvn.packmerge.merge

import com.salyvn.packmerge.core.JsonIo
import com.salyvn.packmerge.core.OverlayEntry
import com.salyvn.packmerge.core.PackFormat
import com.salyvn.packmerge.core.PackFormats
import com.salyvn.packmerge.core.PackMeta
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Raw `overlays.entries` declaration read from an input pack's `pack.mcmeta`. */
data class DeclaredOverlay(
    val pack: String,
    val directory: String,
    val min: PackFormat?,
    val max: PackFormat?,
)

/** What one pack's `pack.mcmeta` claims about the versions and overlays it works on. */
data class DeclaredRange(
    val pack: String,
    val min: PackFormat?,
    val max: PackFormat?,
    val description: String?,
    val overlays: List<DeclaredOverlay> = emptyList(),
) {
    override fun toString(): String = "${min ?: "?"}..${max ?: "?"}"
}

/**
 * Reading and reconciling the version ranges and overlay directories of the packs being merged.
 */
object PackRanges {

    fun read(source: PackSource): DeclaredRange? {
        if ("pack.mcmeta" !in source.files) return null
        val root = try {
            JsonIo.parse(source.readText("pack.mcmeta")).jsonObject
        } catch (failure: Exception) {
            null
        } ?: return DeclaredRange(source.name, null, null, null)

        val pack = root["pack"] as? JsonObject ?: return DeclaredRange(source.name, null, null, null)

        val single = number(pack["pack_format"])
        val (supportedMin, supportedMax) = parseLegacyBounds(pack["supported_formats"])
        val min = format(pack["min_format"]) ?: supportedMin?.let { PackFormat(it) } ?: single?.let { PackFormat(it) }
        val max = format(pack["max_format"]) ?: supportedMax?.let { PackFormat(it) }
        val description = (pack["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val overlays = readOverlays(source.name, root["overlays"] as? JsonObject)
        return DeclaredRange(source.name, min, max, description, overlays)
    }

    private fun readOverlays(packName: String, overlaysObj: JsonObject?): List<DeclaredOverlay> {
        val entries = overlaysObj?.get("entries") as? JsonArray ?: return emptyList()
        return entries.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val dir = (obj["directory"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            val (legacyMin, legacyMax) = parseLegacyBounds(obj["formats"])
            val min = format(obj["min_format"]) ?: legacyMin?.let { PackFormat(it) }
            val max = format(obj["max_format"]) ?: legacyMax?.let { PackFormat(it) } ?: min
            DeclaredOverlay(packName, dir, min, max)
        }
    }

    /**
     * The range the merged pack should claim: the highest floor and the lowest stated ceiling, using
     * minor-aware comparison (`[97, 1]` > `97` for `min_format`, `[97, 0]` < `97` for `max_format`).
     */
    fun reconcile(declared: List<DeclaredRange>, report: MergeReport): Pair<PackFormat, PackFormat>? {
        val withMin = declared.filter { it.min != null }
        if (withMin.isEmpty()) return null

        val floor = withMin.maxWith { a, b -> a.min!!.compareAsMin(b.min!!) }
        val min = floor.min!!
        val ceiling = declared.filter { it.max != null }.minWithOrNull { a, b -> a.max!!.compareAsMax(b.max!!) }
        val newest = PackFormat(PackFormats.formatOf(PackFormats.newestRelease))
        val max = ceiling?.max ?: if (PackFormat.isValidRange(min, newest)) newest else min

        withMin.filter { it.min!!.compareAsMin(min) < 0 }.forEach { behind ->
            report.note(
                "pack.mcmeta",
                "${behind.pack} claims from format ${behind.min} but ${floor.pack} needs $min, so the merged pack starts at $min. " +
                    "${behind.pack} on its own still worked below that.",
                MergeRule.PACK_FORMAT_NOTE,
            )
        }
        if (ceiling == null) {
            report.note(
                "pack.mcmeta",
                "No pack states a maximum format, so the merged pack claims up to $max (${PackFormats.newestRelease}). Pass --versions to say otherwise.",
                MergeRule.PACK_FORMAT_NOTE,
            )
        }
        if (!PackFormat.isValidRange(min, max)) {
            report.error(
                "pack.mcmeta",
                "The packs do not share a version: ${declared.joinToString(", ") { "${it.pack} $it" }}. " +
                    "The merged pack claims the last pack's own range; pass --versions to state one yourself.",
                MergeRule.INCOMPATIBLE_PACK_FORMATS,
            )
            val last = declared.last()
            val fallbackMin = last.min ?: min
            val fallbackMax = last.max?.takeIf { PackFormat.isValidRange(fallbackMin, it) } ?: fallbackMin
            return fallbackMin to fallbackMax
        }
        return min to max
    }

    /**
     * Validates and reconciles `overlays.entries` across all input packs so overlay directories survive in
     * the merged `pack.mcmeta` and have valid format ranges.
     */
    fun reconcileOverlays(declared: List<DeclaredRange>, report: MergeReport): List<OverlayEntry> {
        val grouped = linkedMapOf<String, MutableList<DeclaredOverlay>>()
        for (packRange in declared) {
            for (ov in packRange.overlays) {
                if (!PackMeta.isValidOverlayDir(ov.directory)) {
                    report.error(
                        "pack.mcmeta",
                        "${ov.pack} declares invalid overlay directory '${ov.directory}'; directory names must match [a-z0-9_-]+.",
                        MergeRule.INVALID_OVERLAY_DIRECTORY,
                    )
                    continue
                }
                grouped.getOrPut(ov.directory) { mutableListOf() } += ov
            }
        }

        val newest = PackFormat(PackFormats.formatOf(PackFormats.newestRelease))
        val result = mutableListOf<OverlayEntry>()
        for ((dir, specs) in grouped) {
            val withMin = specs.filter { it.min != null }
            if (withMin.isEmpty()) continue
            val min = withMin.maxWith { a, b -> a.min!!.compareAsMin(b.min!!) }.min!!
            val withMax = specs.filter { it.max != null }
            val max = withMax.minWithOrNull { a, b -> a.max!!.compareAsMax(b.max!!) }?.max
                ?: if (PackFormat.isValidRange(min, newest)) newest else min

            if (!PackFormat.isValidRange(min, max)) {
                val last = specs.last()
                val fallbackMin = last.min ?: min
                val fallbackMax = last.max?.takeIf { PackFormat.isValidRange(fallbackMin, it) } ?: fallbackMin
                report.warn(
                    "pack.mcmeta",
                    "Overlay directory '$dir' has disjoint format bounds across packs (${specs.joinToString(", ") { "${it.pack} ${it.min ?: "?"}..${it.max ?: "?"}" }}); " +
                        "${last.pack}'s range ($fallbackMin..$fallbackMax) is kept.",
                    MergeRule.OVERLAY_FORMAT_CONFLICT,
                )
                result += OverlayEntry(dir, fallbackMin, fallbackMax)
            } else {
                result += OverlayEntry(dir, min, max)
            }
        }
        return result
    }

    private fun parseLegacyBounds(supported: JsonElement?): Pair<Int?, Int?> = when (supported) {
        is JsonArray -> number(supported.firstOrNull()) to number(supported.lastOrNull())
        is JsonObject -> number(supported["min_inclusive"]) to number(supported["max_inclusive"])
        is JsonPrimitive -> number(supported).let { it to it }
        else -> null to null
    }

    private fun number(element: JsonElement?): Int? =
        (element as? JsonPrimitive)?.content?.toIntOrNull()

    /** `46` or `[97, 1]`, the two spellings the client accepts. */
    private fun format(element: JsonElement?): PackFormat? = when (element) {
        is JsonPrimitive -> element.content.toIntOrNull()?.let { PackFormat(it) }
        is JsonArray -> number(element.firstOrNull())?.let { PackFormat(it, number(element.getOrNull(1))) }
        else -> null
    }
}
