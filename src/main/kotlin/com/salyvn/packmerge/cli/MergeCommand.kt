package com.salyvn.packmerge.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.packmerge.core.PackFormats
import com.salyvn.packmerge.core.PackMetaException
import com.salyvn.packmerge.merge.JsonMergeReport
import com.salyvn.packmerge.merge.MergeKind
import com.salyvn.packmerge.merge.MergePlan
import com.salyvn.packmerge.merge.PackMerge
import com.salyvn.packmerge.merge.PackSource
import com.salyvn.packmerge.merge.ZipArtifact
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/**
 * The one command.
 *
 * Packs (directories or `.zip` archives) are given in the order the client would apply them: the last one
 * wins where two of them cannot both be right. The output is written to `--out` and/or `--zip`, and
 * `--dry-run` reports without writing at all.
 */
class MergeCommand : CliktCommand(name = "packmerge") {

    override fun help(context: Context) =
        "Merge resource pack directories or .zip archives into one, stacking what the client stacks and reporting collisions."

    private val packs by argument("pack", help = "Pack directories or .zip archives, lowest priority first")
        .path(mustExist = true)
        .multiple()

    private val out by option("--out", help = "Directory to write the merged pack to").path()

    private val zip by option(
        "--zip",
        help = "Write a byte-deterministic merged .zip archive and print its resource-pack-sha1 digest",
    ).path()

    private val versions by option(
        "--versions",
        help = "Range pack.mcmeta should claim, e.g. 1.21.4..26.2. Default: what the packs themselves claim",
    )

    private val description by option("--description", help = "Description for the merged pack.mcmeta")

    private val force by option("--force", help = "Write into --out or --zip even if the target already exists").flag()

    private val dryRun by option("--dry-run", help = "Report what would be merged and write nothing").flag()

    private val strict by option("--strict", help = "Exit 1 on warnings too").flag()

    private val json by option("--json", help = "Emit machine-readable JSON merge report to stdout").flag()

    override fun run() {
        try {
            execute()
        } catch (failure: IllegalArgumentException) {
            fail(failure.message)
        } catch (failure: PackMetaException) {
            fail(failure.message)
        } catch (failure: IOException) {
            fail(failure.message)
        }
    }

    private fun execute() {
        if (packs.size < 2) fail("Give at least two packs to merge. One pack is already merged.")
        if (out == null && zip == null && !dryRun) {
            fail("Give --out DIR, --zip FILE, or --dry-run.")
        }
        packs.forEach { pack ->
            if (!pack.isDirectory() && !pack.isRegularFile()) {
                fail("$pack is neither a directory nor a .zip archive.")
            }
        }
        val outDir = out
        if (!dryRun && outDir != null && outDir.exists() && outDir.isDirectory() && outDir.listDirectoryEntries().isNotEmpty() && !force) {
            fail("$outDir already has files in it. Pass --force to write into it anyway, or choose an empty directory.")
        }
        val zipPath = zip
        if (!dryRun && zipPath != null && zipPath.exists() && !force) {
            fail("$zipPath already exists. Pass --force to overwrite it.")
        }

        val range = versions?.let { PackFormats.parseRange(it) }
        val sources = packs.mapIndexed { index, path -> PackSource(path, index) }
        val merge = PackMerge(sources, range, description)
        val plan = merge.plan()

        var zipArtifact: ZipArtifact? = null
        if (!dryRun) {
            if (outDir != null) {
                merge.write(plan, outDir)
            }
            if (zipPath != null) {
                zipArtifact = merge.writeZip(plan, zipPath)
            }
        }

        if (json) {
            echo(JsonMergeReport.render(plan, outDir, zipArtifact, dryRun))
        } else {
            val targetLabel = outDir?.fileName?.toString()
                ?: zipPath?.fileName?.toString()
                ?: "(dry-run)"
            report(plan, targetLabel)
            if (dryRun) {
                echo("")
                echo("Nothing written. Drop --dry-run to write ${plan.files.size} file(s).")
            } else {
                echo("")
                if (outDir != null) {
                    echo("Wrote ${plan.files.size} file(s) to $outDir")
                }
                if (zipArtifact != null) {
                    echo("Zipped ${plan.files.size} file(s) to ${zipArtifact.path} (${zipArtifact.sizeBytes} bytes)")
                    echo("resource-pack-sha1=${zipArtifact.sha1}")
                }
            }
        }

        if (plan.errors > 0 || (strict && plan.warnings > 0)) throw ProgramResult(1)
    }

    private fun report(plan: MergePlan, targetLabel: String) {
        echo("== ${plan.packs.joinToString(" + ") { it.name }} -> $targetLabel")
        plan.packs.forEach { pack -> echo("   ${pack.name.padEnd(28)} ${pack.files.size} file(s)") }
        echo("")
        echo("   ${plan.files.size} file(s), pack.mcmeta claiming ${plan.range.first}..${plan.range.second}")
        LABELS.forEach { (kind, label) ->
            plan.combined[kind]?.let { count -> echo("   $count $label") }
        }

        if (plan.issues.isNotEmpty()) {
            echo("")
            plan.issues.forEach { issue ->
                echo("   ${issue.level.name.lowercase().padEnd(7)} ${issue.path}")
                echo("           ${issue.message}")
            }
        }
        echo("")
        echo("${plan.errors} error(s), ${plan.warnings} warning(s), ${plan.notes} note(s)")
    }

    private fun fail(message: String?): Nothing {
        echo(message ?: "The command could not be carried out.", err = true)
        throw ProgramResult(2)
    }

    private companion object {
        /** Only the kinds worth counting in a summary line, in the order a reader cares about them. */
        val LABELS: List<Pair<MergeKind, String>> = listOf(
            MergeKind.ITEM_DEFINITION to "item model definition(s) unioned",
            MergeKind.LEGACY_MODEL to "legacy model(s) with their overrides unioned",
            MergeKind.FONT to "font(s) with their providers concatenated",
            MergeKind.SOUNDS to "sounds.json merged",
            MergeKind.LANG to "translation file(s) merged",
            MergeKind.ATLAS to "atlas file(s) with their sources concatenated",
            MergeKind.SINGLE_OWNER to "single-owner file(s) where a later pack won",
        )
    }
}
