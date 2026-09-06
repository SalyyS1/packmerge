package com.salyvn.packmerge.merge

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * One pack on disk, in the order it was given on the command line.
 *
 * Order is the whole contract of a merge: later packs win where two packs cannot both be right, which is
 * how the client behaves for two packs enabled at once, and how anyone reading the command expects it to
 * read. [position] is kept so a report can say which pack a file came from by name rather than by path.
 */
class PackSource(val root: Path, val position: Int) {
    val name: String = root.fileName?.name ?: root.toString()

    /** Every file in the pack, as forward-slash paths relative to its root, sorted. */
    val files: List<String> by lazy {
        if (!root.exists()) return@lazy emptyList()
        Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() }
                .map { file -> root.relativize(file).joinToString("/") { part -> part.toString() } }
                .sorted()
                .toList()
        }
    }

    fun read(relative: String): ByteArray = Files.readAllBytes(root.resolve(relative))

    fun readText(relative: String): String = Files.readString(root.resolve(relative))

    override fun toString(): String = name
}

/** Content identity, so two packs shipping the same texture is not reported as a conflict. */
fun digest(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
