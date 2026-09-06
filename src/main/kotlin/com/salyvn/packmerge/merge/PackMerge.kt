package com.salyvn.packmerge.merge

import com.salyvn.packmerge.core.PackFormat
import com.salyvn.packmerge.core.PackMeta
import java.nio.file.Files
import java.nio.file.Path

class MergePlan(
    val packs: List<PackSource>,
    val files: List<MergedFile>,
    val issues: List<Issue>,
    val range: Pair<PackFormat, PackFormat>,
) {
    val errors: Int get() = issues.count { it.level == IssueLevel.ERROR }
    val warnings: Int get() = issues.count { it.level == IssueLevel.WARNING }
    val notes: Int get() = issues.count { it.level == IssueLevel.NOTE }

    /** How many files were actually combined, or lost to a later pack, by the rule that handled them. */
    val combined: Map<MergeKind, Int>
        get() = files.filter { it.merged }.groupingBy { it.kind }.eachCount()
}

/**
 * The merge itself.
 *
 * Every file path present in any pack is visited once. A path only one pack has is copied. A path several
 * packs have is combined by the rule for its kind ([MergeRules]), and when there is no rule — a texture, a
 * shader, a blockstate — the later pack wins and the report says which packs were involved, because a
 * silently overwritten file is the thing a merge is supposed to surface.
 *
 * Identical content is not a conflict. Two plugin packs shipping the same copy of a vanilla model or the
 * same font atlas is normal, and reporting it would bury the twenty lines that matter under a thousand that
 * do not.
 */
class PackMerge(
    private val packs: List<PackSource>,
    private val versions: Pair<PackFormat, PackFormat>?,
    private val description: String?,
) {

    fun plan(): MergePlan {
        val report = MergeReport()
        val files = mutableListOf<MergedFile>()

        val declared = packs.mapNotNull { PackRanges.read(it) }
        val range = versions ?: PackRanges.reconcile(declared, report) ?: (PackFormat(46) to PackFormat(46))

        // Every path in any pack, with the packs that have it, in command-line order.
        val owners = linkedMapOf<String, MutableList<PackSource>>()
        packs.forEach { pack ->
            pack.files.forEach { relative -> owners.getOrPut(relative) { mutableListOf() } += pack }
        }

        owners.entries.sortedBy { it.key }.forEach { (relative, sources) ->
            val kind = MergeRules.of(relative)
            if (kind == MergeKind.PACK_META) return@forEach

            if (sources.size == 1) {
                files += MergedFile(relative, sources.single().read(relative), listOf(sources.single().name), kind)
                return@forEach
            }

            val contents = sources.map { it.read(relative) }
            if (contents.map { digest(it) }.distinct().size == 1) {
                files += MergedFile(relative, contents.first(), sources.map { it.name }, kind)
                return@forEach
            }

            val merged = combine(relative, kind, sources, report)
            if (merged != null) {
                files += MergedFile(relative, merged, sources.map { it.name }, kind, merged = true)
            } else {
                report.warn(
                    relative,
                    "${sources.size} packs ship this file with different content and it has one owner; " +
                        "${sources.last().name}'s copy is the one written.",
                )
                files += MergedFile(relative, contents.last(), sources.map { it.name }, MergeKind.SINGLE_OWNER, merged = true)
            }
        }

        files += MergedFile(
            "pack.mcmeta",
            PackMeta.render(description ?: descriptionFrom(declared), range.first, range.second).toByteArray(),
            packs.map { it.name },
            MergeKind.PACK_META,
        )

        return MergePlan(packs, files.sortedBy { it.relative }, report.issues, range)
    }

    fun write(plan: MergePlan, out: Path) {
        plan.files.forEach { file ->
            val target = out.resolve(file.relative)
            Files.createDirectories(target.parent)
            Files.write(target, file.bytes)
        }
    }

    private fun combine(relative: String, kind: MergeKind, sources: List<PackSource>, report: MergeReport): ByteArray? =
        when (kind) {
            MergeKind.FONT -> FontMerge.merge(relative, sources, report)
            MergeKind.SOUNDS -> ObjectMerge.mergeObjects(relative, sources, report, IssueLevel.WARNING)
            MergeKind.LANG -> ObjectMerge.mergeObjects(relative, sources, report, IssueLevel.NOTE)
            MergeKind.ATLAS -> ObjectMerge.mergeArrayUnder(relative, "sources", sources, report)
            MergeKind.LEGACY_MODEL -> ItemModelMerge.mergeLegacyModel(relative, sources, report)
            MergeKind.ITEM_DEFINITION -> ItemModelMerge.mergeDefinition(relative, sources, report)
            MergeKind.PACK_META, MergeKind.SINGLE_OWNER -> null
        }

    /** The first description the packs offer, so the merged pack is recognisable in the pack list. */
    private fun descriptionFrom(declared: List<DeclaredRange>): String =
        declared.firstNotNullOfOrNull { it.description } ?: "merged pack"
}
