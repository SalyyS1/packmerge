package com.salyvn.packmerge.merge

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path

@Serializable
data class PackSummaryJson(
    val name: String,
    val source: String,
    val fileCount: Int,
)

@Serializable
data class OverlaySummaryJson(
    val directory: String,
    val minFormat: String,
    val maxFormat: String,
)

@Serializable
data class IssueJson(
    val rule: String,
    val level: String,
    val path: String,
    val message: String,
)

@Serializable
data class MergeReportPayload(
    val packs: List<PackSummaryJson>,
    val outputDir: String? = null,
    val zipPath: String? = null,
    val zipSha1: String? = null,
    val zipSha256: String? = null,
    val dryRun: Boolean,
    val fileCount: Int,
    val minFormat: String,
    val maxFormat: String,
    val overlays: List<OverlaySummaryJson> = emptyList(),
    val combinedCounts: Map<String, Int>,
    val errorCount: Int,
    val warningCount: Int,
    val noteCount: Int,
    val issues: List<IssueJson>,
)

object JsonMergeReport {
    private val FORMAT = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }

    fun render(
        plan: MergePlan,
        outDir: Path?,
        zipArtifact: ZipArtifact?,
        dryRun: Boolean,
    ): String {
        val payload = MergeReportPayload(
            packs = plan.packs.map { p ->
                PackSummaryJson(
                    name = p.name,
                    source = p.root.toString(),
                    fileCount = p.files.size,
                )
            },
            outputDir = outDir?.toString(),
            zipPath = zipArtifact?.path?.toString(),
            zipSha1 = zipArtifact?.sha1,
            zipSha256 = zipArtifact?.sha256,
            dryRun = dryRun,
            fileCount = plan.files.size,
            minFormat = plan.range.first.toString(),
            maxFormat = plan.range.second.toString(),
            overlays = plan.overlays.map { ov ->
                OverlaySummaryJson(
                    directory = ov.directory,
                    minFormat = ov.min.toString(),
                    maxFormat = ov.max.toString(),
                )
            },
            combinedCounts = plan.combined.entries.associate { (k, v) -> k.name.lowercase() to v },
            errorCount = plan.errors,
            warningCount = plan.warnings,
            noteCount = plan.notes,
            issues = plan.issues.map { issue ->
                IssueJson(
                    rule = issue.rule.id,
                    level = issue.level.name.lowercase(),
                    path = issue.path,
                    message = issue.message,
                )
            },
        )
        return FORMAT.encodeToString(payload)
    }
}
