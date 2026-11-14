package com.salyvn.packmerge.merge

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * One pack on disk (either an unpacked directory or a `.zip` archive), in the order it was given on the
 * command line.
 *
 * Order is the whole contract of a merge: later packs win where two packs cannot both be right, which is
 * how the client behaves for two packs enabled at once, and how anyone reading the command expects it to
 * read. [position] is kept so a report can say which pack a file came from by name rather than by path.
 */
class PackSource(val root: Path, val position: Int) {
    val name: String = (root.fileName?.name ?: root.toString()).removeSuffix(".zip")

    private val isZip: Boolean = root.isRegularFile()

    init {
        require(!root.exists() || root.isDirectory() || root.isRegularFile()) {
            "$root is neither a pack directory nor a .zip archive."
        }
    }

    /** Every file in the pack, as forward-slash paths relative to its root, sorted. */
    val files: List<String> by lazy {
        if (!root.exists()) return@lazy emptyList()
        if (isZip) {
            listZipEntries()
        } else {
            Files.walk(root).use { stream ->
                stream.filter { it.isRegularFile() }
                    .map { file -> root.relativize(file).joinToString("/") { part -> part.toString() } }
                    .sorted()
                    .toList()
            }
        }
    }

    fun read(relative: String): ByteArray {
        if (!isZip) {
            val resolved = safeResolve(relative)
            val size = Files.size(resolved)
            if (size > MAX_ENTRY_BYTES) {
                throw IOException("Entry '$relative' in $name exceeds ${MAX_ENTRY_BYTES}B safety limit")
            }
            return Files.readAllBytes(resolved)
        }
        ZipFile(root.toFile()).use { zip ->
            val entry = findZipEntry(zip, relative)
                ?: throw IOException("Missing entry '$relative' in zip pack $root")
            if (entry.size > MAX_ENTRY_BYTES) {
                throw IOException("Zip entry '$relative' in $name exceeds ${MAX_ENTRY_BYTES}B safety limit")
            }
            zip.getInputStream(entry).use { input ->
                val bytes = input.readNBytes((MAX_ENTRY_BYTES + 1).toInt())
                if (bytes.size > MAX_ENTRY_BYTES) {
                    throw IOException("Zip entry '$relative' in $name exceeds ${MAX_ENTRY_BYTES}B decompressed limit")
                }
                return bytes
            }
        }
    }

    fun readText(relative: String): String = String(read(relative), StandardCharsets.UTF_8)

    override fun toString(): String = name

    private fun safeResolve(relative: String): Path {
        val base = root.toAbsolutePath().normalize()
        val target = base.resolve(relative).normalize()
        if (!target.startsWith(base)) {
            throw IOException("Refusing path traversal entry '$relative' in $root")
        }
        return target
    }

    private fun listZipEntries(): List<String> {
        try {
            ZipFile(root.toFile()).use { zip ->
                val out = mutableListOf<String>()
                val entries = zip.entries()
                var count = 0
                while (entries.hasMoreElements()) {
                    count++
                    if (count > MAX_ZIP_ENTRIES) {
                        throw IOException("Zip pack $root exceeds maximum entry count ($MAX_ZIP_ENTRIES)")
                    }
                    val entry = entries.nextElement()
                    if (!entry.isDirectory) {
                        val normalized = normalizeZipName(entry.name)
                        if (normalized != null) out += normalized
                    }
                }
                return out.distinct().sorted()
            }
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            throw IOException("$root is not a readable .zip resource pack (${failure.message})", failure)
        }
    }

    private fun findZipEntry(zip: ZipFile, relative: String): java.util.zip.ZipEntry? {
        zip.getEntry(relative)?.takeIf { !it.isDirectory }?.let { return it }
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val candidate = entries.nextElement()
            if (!candidate.isDirectory && normalizeZipName(candidate.name) == relative) {
                return candidate
            }
        }
        return null
    }

    private fun normalizeZipName(raw: String): String? {
        val clean = raw.replace('\\', '/').trimStart('/')
        if (clean.isEmpty()) return null
        val segments = clean.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw IOException("Refusing unsafe zip entry '$raw' in $root")
        }
        return clean
    }

    private companion object {
        const val MAX_ZIP_ENTRIES = 25_000
        const val MAX_ENTRY_BYTES = 64 * 1024 * 1024L
    }
}

/** Content identity, so two packs shipping the same texture is not reported as a conflict. */
fun digest(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** SHA-1 hex digest matching Minecraft's `resource-pack-sha1` property in `server.properties`. */
fun sha1Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
