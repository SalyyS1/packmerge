package com.salyvn.packmerge.merge

import com.salyvn.packmerge.core.OverlayEntry
import com.salyvn.packmerge.core.PackFormat

data class OverlayDedupeResult(
    val files: List<MergedFile>,
    val overlays: List<OverlayEntry>,
    val dedupedFiles: Int,
    val savedBytes: Long,
)

/**
 * Dual-era overlay deduplication and perceptual PNG duplicate analysis (`--dedupe-overlays`, `--phash`).
 */
object AssetDeduplicator {
    private const val MAX_CROSS_PATH_WARNINGS = 25

    /**
     * Coalesces single-owner `.png` collisions when `--phash` is active:
     * - If every pack's PNG decodes to the exact same RGBA pixel raster (`pixelSha256`), keeps the smallest
     *   encoded file and emits a `PM013` informational note instead of a `PM006` overwrite warning.
     * - If pixels differ, keeps the last pack's copy and reports `PM013` if perceptually near-identical
     *   (`hamming <= phashThreshold`), or `PM006` otherwise.
     */
    fun resolveSingleOwner(
        relative: String,
        sources: List<PackSource>,
        contents: List<ByteArray>,
        options: MergeOptions,
        report: MergeReport,
    ): MergedFile {
        if (options.phash && relative.endsWith(".png", ignoreCase = true)) {
            val inspected = contents.map { PerceptualHash.inspect(it) }
            if (inspected.all { it != null }) {
                val fps = inspected.map { it!! }
                if (fps.map { it.pixelSha256 }.distinct().size == 1) {
                    val bestIdx = contents.indices.minBy { contents[it].size }
                    val maxBytes = contents.maxOf { it.size }
                    val minBytes = contents[bestIdx].size
                    val saved = maxBytes - minBytes
                    val fp = fps.first()
                    report.note(
                        relative,
                        "${sources.size} packs ship re-encoded PNGs with identical ${fp.width}x${fp.height} RGBA pixels (${fp.phashHex}); " +
                            "kept ${sources[bestIdx].name}'s compact encoding (saved $saved byte(s)).",
                        MergeRule.PERCEPTUAL_TEXTURE_DUPLICATE,
                    )
                    return MergedFile(relative, contents[bestIdx], sources.map { it.name }, MergeKind.SINGLE_OWNER, merged = false)
                }

                val winnerFp = fps.last()
                val minDist = fps.dropLast(1).minOf { PerceptualHash.distance(it, winnerFp) }
                if (minDist <= options.phashThreshold) {
                    val closestIdx = fps.dropLast(1).indices.minBy { PerceptualHash.distance(fps[it], winnerFp) }
                    report.warn(
                        relative,
                        "${sources.size} packs ship perceptually similar textures (${sources[closestIdx].name} ${fps[closestIdx].phashHex} vs " +
                            "${sources.last().name} ${winnerFp.phashHex}, hamming=$minDist/64); ${sources.last().name}'s copy is written.",
                        MergeRule.PERCEPTUAL_TEXTURE_DUPLICATE,
                    )
                    return MergedFile(relative, contents.last(), sources.map { it.name }, MergeKind.SINGLE_OWNER, merged = true)
                }
            }
        }

        report.warn(
            relative,
            "${sources.size} packs ship this file with different content and it has one owner; " +
                "${sources.last().name}'s copy is the one written.",
            MergeRule.SINGLE_OWNER_OVERWRITE,
        )
        return MergedFile(relative, contents.last(), sources.map { it.name }, MergeKind.SINGLE_OWNER, merged = true)
    }

    /**
     * Scans distinct `.png` paths across the merged pack to detect duplicate or re-encoded textures shipped
     * under separate plugin namespaces (`assets/oraxen/textures/...` vs `assets/nexo/textures/...`).
     */
    fun scanCrossPathDuplicates(
        files: List<MergedFile>,
        overlayDirs: Set<String>,
        options: MergeOptions,
        report: MergeReport,
    ): Int {
        if (!options.phash) return 0
        val entries = files.mapNotNull { file ->
            if (!file.relative.endsWith(".png", ignoreCase = true)) return@mapNotNull null
            val firstSeg = file.relative.substringBefore('/')
            if (firstSeg in overlayDirs) return@mapNotNull null
            PerceptualHash.inspect(file.bytes)?.let { fp -> file to fp }
        }
        if (entries.size < 2) return 0

        var duplicatePairs = 0
        var emitted = 0
        for (i in 0 until entries.size) {
            val (fileA, fpA) = entries[i]
            for (j in i + 1 until entries.size) {
                val (fileB, fpB) = entries[j]
                val dist = PerceptualHash.distance(fpA, fpB)
                if (dist <= options.phashThreshold) {
                    duplicatePairs++
                    if (emitted < MAX_CROSS_PATH_WARNINGS) {
                        emitted++
                        val kindLabel = if (fpA.pixelSha256 == fpB.pixelSha256) {
                            "pixel-identical (${fpA.width}x${fpA.height} RGBA)"
                        } else {
                            "perceptually near-identical (hamming=$dist/64, ${fpA.phashHex} vs ${fpB.phashHex})"
                        }
                        report.warn(
                            fileB.relative,
                            "Texture is $kindLabel to ${fileA.relative}; consider sharing a single atlas texture reference.",
                            MergeRule.PERCEPTUAL_TEXTURE_DUPLICATE,
                        )
                    }
                }
            }
        }
        return duplicatePairs
    }

    /**
     * Prunes files inside `overlays.entries` directories that are byte-identical (or RGBA pixel-identical when
     * `options.phash` is active) to the effective underlying root or lower-overlay file across the overlay's
     * entire `[min_format, max_format]` interval.
     */
    fun deduplicateOverlays(
        files: List<MergedFile>,
        overlays: List<OverlayEntry>,
        options: MergeOptions,
        report: MergeReport,
    ): OverlayDedupeResult {
        if (!options.dedupeOverlays || overlays.isEmpty()) {
            return OverlayDedupeResult(files, overlays, 0, 0L)
        }

        val byPath = files.associateBy { it.relative }
        val overlayIndexByDir = overlays.mapIndexed { idx, ov -> ov.directory to idx }.toMap()
        val removedPaths = linkedSetOf<String>()
        val removedPerOverlay = mutableMapOf<String, Int>()
        val initialPerOverlay = mutableMapOf<String, Int>()
        var savedBytes = 0L

        for (file in files) {
            val slash = file.relative.indexOf('/')
            if (slash <= 0) continue
            val dir = file.relative.substring(0, slash)
            val ovIndex = overlayIndexByDir[dir] ?: continue
            initialPerOverlay[dir] = (initialPerOverlay[dir] ?: 0) + 1

            val innerPath = file.relative.substring(slash + 1)
            if (!innerPath.startsWith("assets/")) continue

            val currentOverlay = overlays[ovIndex]
            val overlappingEarlier = (0 until ovIndex).mapNotNull { earlierIdx ->
                val earlierOv = overlays[earlierIdx]
                val candidate = byPath["${earlierOv.directory}/$innerPath"]
                if (candidate != null && candidate.relative !in removedPaths && rangesOverlap(earlierOv, currentOverlay)) {
                    earlierOv to candidate
                } else {
                    null
                }
            }

            val effectiveBase: MergedFile? = if (overlappingEarlier.isEmpty()) {
                byPath[innerPath]
            } else {
                val (topEarlierOv, topCandidate) = overlappingEarlier.last()
                if (rangeCovers(topEarlierOv, currentOverlay)) topCandidate else null
            }

            if (effectiveBase != null && areAssetsEquivalent(effectiveBase.bytes, file.bytes, file.relative, options.phash)) {
                removedPaths += file.relative
                removedPerOverlay[dir] = (removedPerOverlay[dir] ?: 0) + 1
                savedBytes += file.bytes.size.toLong()
                report.note(
                    file.relative,
                    "Pruned redundant overlay file matching '${effectiveBase.relative}' (${file.bytes.size} byte(s) saved across format ${currentOverlay.min}..${currentOverlay.max}).",
                    MergeRule.OVERLAY_ASSET_DEDUPLICATED,
                )
            }
        }

        if (removedPaths.isEmpty()) {
            return OverlayDedupeResult(files, overlays, 0, 0L)
        }

        val keptFiles = files.filterNot { it.relative in removedPaths }
        val keptOverlays = overlays.filter { ov ->
            val initial = initialPerOverlay[ov.directory] ?: 0
            val removed = removedPerOverlay[ov.directory] ?: 0
            initial == 0 || removed < initial
        }

        return OverlayDedupeResult(
            files = keptFiles,
            overlays = keptOverlays,
            dedupedFiles = removedPaths.size,
            savedBytes = savedBytes,
        )
    }

    private fun areAssetsEquivalent(a: ByteArray, b: ByteArray, path: String, usePixelHash: Boolean): Boolean {
        if (a.contentEquals(b)) return true
        if (usePixelHash && path.endsWith(".png", ignoreCase = true)) {
            val fpA = PerceptualHash.inspect(a) ?: return false
            val fpB = PerceptualHash.inspect(b) ?: return false
            return fpA.pixelSha256 == fpB.pixelSha256
        }
        return false
    }

    /** True if `[a.min, a.max]` and `[b.min, b.max]` share at least one minor-qualified pack format. */
    fun rangesOverlap(a: OverlayEntry, b: OverlayEntry): Boolean =
        PackFormat.isValidRange(maxMin(a.min, b.min), minMax(a.max, b.max))

    /** True if `[cover.min, cover.max]` is a superset of `[target.min, target.max]`. */
    fun rangeCovers(cover: OverlayEntry, target: OverlayEntry): Boolean =
        cover.min.compareAsMin(target.min) <= 0 && cover.max.compareAsMax(target.max) >= 0

    private fun maxMin(a: PackFormat, b: PackFormat): PackFormat = if (a.compareAsMin(b) >= 0) a else b

    private fun minMax(a: PackFormat, b: PackFormat): PackFormat = if (a.compareAsMax(b) <= 0) a else b
}
