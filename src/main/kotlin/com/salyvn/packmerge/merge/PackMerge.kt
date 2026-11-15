package com.salyvn.packmerge.merge

import com.salyvn.packmerge.core.OverlayEntry
import com.salyvn.packmerge.core.PackFormat
import com.salyvn.packmerge.core.PackMeta
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ZipArtifact(
    val path: Path,
    val sha1: String,
    val sha256: String,
    val sizeBytes: Long,
)

data class MergeOptions(
    val dedupeOverlays: Boolean = false,
    val phash: Boolean = false,
    val phashThreshold: Int = 4,
    val pruneOverrides: Boolean = false,
    val auditSounds: Boolean = false,
    val auditAtlas: Boolean = false,
    val auditAudio: Boolean = false,
) {
    init {
        require(phashThreshold in 0..64) { "--phash-threshold must be between 0 and 64 (got $phashThreshold)." }
    }

    val hasSinglePackTransforms: Boolean
        get() = dedupeOverlays || phash || pruneOverrides || auditSounds || auditAtlas || auditAudio
}

class MergePlan(
    val packs: List<PackSource>,
    val files: List<MergedFile>,
    val issues: List<Issue>,
    val range: Pair<PackFormat, PackFormat>,
    val overlays: List<OverlayEntry> = emptyList(),
    val dedupedOverlayFiles: Int = 0,
    val savedOverlayBytes: Long = 0L,
    val prunedOverrides: Int = 0,
    val perceptualDuplicates: Int = 0,
) {
    val errors: Int get() = issues.count { it.level == IssueLevel.ERROR }
    val warnings: Int get() = issues.count { it.level == IssueLevel.WARNING }
    val notes: Int get() = issues.count { it.level == IssueLevel.NOTE }

    /** How many files were actually combined, or lost to a later pack, by the rule that handled them. */
    val combined: Map<MergeKind, Int>
        get() = files.filter { it.merged }.groupingBy { it.kind }.eachCount()
}

/**
 * Coordinates the multi-pack resource merge across root `assets/` and declared `overlays.entries` directories.
 */
class PackMerge(
    private val packs: List<PackSource>,
    private val versions: Pair<PackFormat, PackFormat>?,
    private val description: String?,
    private val options: MergeOptions = MergeOptions(),
) {

    fun plan(): MergePlan {
        val report = MergeReport()
        val rawFiles = mutableListOf<MergedFile>()

        val declared = packs.mapNotNull { PackRanges.read(it) }
        val range = versions ?: PackRanges.reconcile(declared, report) ?: (PackFormat(46) to PackFormat(46))
        val reconciledOverlays = PackRanges.reconcileOverlays(declared, report)
        val initialOverlayDirs = reconciledOverlays.map { it.directory }.toSet()

        val owners = linkedMapOf<String, MutableList<PackSource>>()
        packs.forEach { pack ->
            pack.files.forEach { relative -> owners.getOrPut(relative) { mutableListOf() } += pack }
        }

        owners.entries.sortedBy { it.key }.forEach { (relative, sources) ->
            val kind = MergeRules.of(relative, initialOverlayDirs)
            if (kind == MergeKind.PACK_META) return@forEach

            if (sources.size == 1) {
                rawFiles += MergedFile(relative, sources.single().read(relative), listOf(sources.single().name), kind)
                return@forEach
            }

            val contents = sources.map { it.read(relative) }
            if (contents.map { digest(it) }.distinct().size == 1) {
                rawFiles += MergedFile(relative, contents.first(), sources.map { it.name }, kind)
                return@forEach
            }

            val merged = combine(relative, kind, sources, report)
            if (merged != null) {
                rawFiles += MergedFile(relative, merged, sources.map { it.name }, kind, merged = true)
            } else {
                rawFiles += AssetDeduplicator.resolveSingleOwner(relative, sources, contents, options, report)
            }
        }

        val prunedResult = if (options.pruneOverrides) {
            ModelOverridePruner.pruneAll(rawFiles, initialOverlayDirs, report)
        } else {
            PruneResult(rawFiles, 0)
        }

        val dedupedResult = AssetDeduplicator.deduplicateOverlays(
            files = prunedResult.files,
            overlays = reconciledOverlays,
            options = options,
            report = report,
        )

        val activeOverlays = dedupedResult.overlays
        val activeOverlayDirs = activeOverlays.map { it.directory }.toSet()
        val compiledFiles = dedupedResult.files.toMutableList()

        val crossPathDuplicates = AssetDeduplicator.scanCrossPathDuplicates(
            files = compiledFiles,
            overlayDirs = activeOverlayDirs,
            options = options,
            report = report,
        )

        SoundAtlasValidator.validateAll(
            files = compiledFiles,
            overlays = activeOverlays,
            auditMissingAssets = options.auditSounds,
            report = report,
        )

        AtlasPackingSimulator.validateAll(
            files = compiledFiles,
            overlays = activeOverlays,
            auditAtlas = options.auditAtlas,
            report = report,
        )

        AudioBitDepthAuditor.validateAll(
            files = compiledFiles,
            overlays = activeOverlays,
            report = report,
        )

        compiledFiles += MergedFile(
            "pack.mcmeta",
            PackMeta.render(description ?: descriptionFrom(declared), range.first, range.second, activeOverlays).toByteArray(),
            packs.map { it.name },
            MergeKind.PACK_META,
        )

        val samePathPerceptualCount = report.issues.count {
            it.rule == MergeRule.PERCEPTUAL_TEXTURE_DUPLICATE
        }

        return MergePlan(
            packs = packs,
            files = compiledFiles.sortedBy { it.relative },
            issues = report.issues,
            range = range,
            overlays = activeOverlays,
            dedupedOverlayFiles = dedupedResult.dedupedFiles,
            savedOverlayBytes = dedupedResult.savedBytes,
            prunedOverrides = prunedResult.prunedEntries,
            perceptualDuplicates = maxOf(crossPathDuplicates, samePathPerceptualCount),
        )
    }

    fun write(plan: MergePlan, out: Path) {
        plan.files.forEach { file ->
            val target = out.resolve(file.relative)
            Files.createDirectories(target.parent)
            Files.write(target, file.bytes)
        }
    }

    /**
     * Writes a byte-reproducible `.zip` archive with sorted entry paths and a fixed epoch timestamp so CDN
     * URLs and `server.properties` `resource-pack-sha1` hashes stay identical when pack contents do not change.
     */
    fun writeZip(plan: MergePlan, zipTarget: Path): ZipArtifact {
        zipTarget.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        BufferedOutputStream(Files.newOutputStream(zipTarget)).use { rawOut ->
            ZipOutputStream(rawOut).use { zos ->
                for (file in plan.files.sortedBy { it.relative }) {
                    val entry = ZipEntry(file.relative).apply {
                        lastModifiedTime = FIXED_ZIP_TIME
                    }
                    zos.putNextEntry(entry)
                    zos.write(file.bytes)
                    zos.closeEntry()
                }
            }
        }
        val zipBytes = Files.readAllBytes(zipTarget)
        return ZipArtifact(
            path = zipTarget,
            sha1 = sha1Hex(zipBytes),
            sha256 = digest(zipBytes),
            sizeBytes = zipBytes.size.toLong(),
        )
    }

    private fun combine(relative: String, kind: MergeKind, sources: List<PackSource>, report: MergeReport): ByteArray? =
        when (kind) {
            MergeKind.FONT -> FontMerge.merge(relative, sources, report)
            MergeKind.SOUNDS -> SoundAtlasValidator.mergeSounds(relative, sources, report)
            MergeKind.LANG -> ObjectMerge.mergeObjects(relative, sources, report, IssueLevel.NOTE, MergeRule.ADDITIVE_MERGE_SUMMARY)
            MergeKind.ATLAS -> ObjectMerge.mergeArrayUnder(relative, "sources", sources, report)
            MergeKind.LEGACY_MODEL -> ItemModelMerge.mergeLegacyModel(relative, sources, report)
            MergeKind.ITEM_DEFINITION -> ItemModelMerge.mergeDefinition(relative, sources, report)
            MergeKind.PACK_META, MergeKind.SINGLE_OWNER -> null
        }

    private fun descriptionFrom(declared: List<DeclaredRange>): String =
        declared.firstNotNullOfOrNull { it.description } ?: "merged pack"

    private companion object {
        /** 1980-01-01T00:00:00Z, the earliest timestamp representable in standard ZIP headers. */
        val FIXED_ZIP_TIME: FileTime = FileTime.fromMillis(315_532_800_000L)
    }
}
