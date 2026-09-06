package com.salyvn.packmerge.merge

/** Something worth telling the person running the merge, with the file it is about. */
data class Issue(val level: IssueLevel, val path: String, val message: String)

enum class IssueLevel { ERROR, WARNING, NOTE }

/**
 * One file the merge would write.
 *
 * [merged] separates the two ways more than one pack can contribute: `true` means the content was combined
 * or a later pack's copy won, `false` means every pack shipped the same bytes and there was nothing to
 * decide. A summary that counts the second kind as work done would inflate every number in it.
 */
class MergedFile(
    val relative: String,
    val bytes: ByteArray,
    val from: List<String>,
    val kind: MergeKind,
    val merged: Boolean = false,
)

/** Collects issues while a merge runs, so the mergers do not each need the plumbing. */
class MergeReport {
    private val collected = mutableListOf<Issue>()

    fun error(path: String, message: String) = add(IssueLevel.ERROR, path, message)

    fun warn(path: String, message: String) = add(IssueLevel.WARNING, path, message)

    fun note(path: String, message: String) = add(IssueLevel.NOTE, path, message)

    private fun add(level: IssueLevel, path: String, message: String) {
        collected += Issue(level, path, message)
    }

    /** Errors first, then warnings, then notes, each group in the order the files were visited. */
    val issues: List<Issue> get() = collected.sortedBy { it.level.ordinal }

    val errors: Int get() = collected.count { it.level == IssueLevel.ERROR }

    val warnings: Int get() = collected.count { it.level == IssueLevel.WARNING }

    val notes: Int get() = collected.count { it.level == IssueLevel.NOTE }
}
