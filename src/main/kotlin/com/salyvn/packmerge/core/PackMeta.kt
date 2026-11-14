package com.salyvn.packmerge.core

/** A pack format, which since resource pack format 65 has a minor component as well as a major one. */
data class PackFormat(val major: Int, val minor: Int? = null) {
    init {
        require(major >= 0) { "A pack format cannot be negative" }
        require(minor == null || minor >= 0) { "A pack format's minor version cannot be negative" }
    }

    /** `15` or `[97, 1]`, the compact form the client's codec accepts for both spellings. */
    fun toJson(): Json = if (minor == null) Json.Num(major) else Json.Arr(listOf(Json.Num(major), Json.Num(minor)))

    /**
     * In `min_format`, a bare major `97` means `[97, 0]`.
     */
    fun compareAsMin(other: PackFormat): Int {
        val cmp = major.compareTo(other.major)
        if (cmp != 0) return cmp
        return (minor ?: 0).compareTo(other.minor ?: 0)
    }

    /**
     * In `max_format`, a bare major `97` means any minor of 97 (`97.*`), which is wider than `[97, 1]`.
     */
    fun compareAsMax(other: PackFormat): Int {
        val cmp = major.compareTo(other.major)
        if (cmp != 0) return cmp
        return (minor ?: Int.MAX_VALUE).compareTo(other.minor ?: Int.MAX_VALUE)
    }

    override fun toString(): String = if (minor == null) "$major" else "$major.$minor"

    companion object {
        fun isValidRange(min: PackFormat, max: PackFormat): Boolean {
            if (min.major != max.major) return min.major < max.major
            val minMinor = min.minor ?: 0
            val maxMinor = max.minor ?: Int.MAX_VALUE
            return minMinor <= maxMinor
        }
    }
}

/** Reconciled `overlays.entries` declaration to emit into `pack.mcmeta`. */
data class OverlayEntry(
    val directory: String,
    val min: PackFormat,
    val max: PackFormat,
)

/** Raised when a requested version range cannot be expressed in a `pack.mcmeta` the client will accept. */
class PackMetaException(message: String) : Exception(message)

/**
 * `pack.mcmeta`, built to the rules the client actually enforces across pre-minor (<= 64) and modern (65+ / 26.3) eras.
 */
object PackMeta {
    /** `PackFormat.lastPreMinorVersion(CLIENT_RESOURCES)`. */
    const val LAST_PRE_MINOR_FORMAT: Int = 64

    /** `validatePackFormatForRange` refuses a multi-version pack whose main format is below this. */
    const val MULTI_VERSION_FLOOR: Int = 15

    private val OVERLAY_DIR_REGEX = Regex("^[a-z0-9_-]+$")

    fun isValidOverlayDir(directory: String): Boolean = OVERLAY_DIR_REGEX.matches(directory)

    fun build(
        description: String,
        min: PackFormat,
        max: PackFormat,
        overlays: List<OverlayEntry> = emptyList(),
    ): Json.Obj {
        if (!PackFormat.isValidRange(min, max)) {
            throw PackMetaException("min_format ($min) is greater than max_format ($max)")
        }
        val pack = mutableListOf<Pair<String, Json>>("description" to Json.Text(description))
        if (min.major > LAST_PRE_MINOR_FORMAT) {
            pack += "min_format" to min.toJson()
            pack += "max_format" to max.toJson()
        } else {
            if (min.major < MULTI_VERSION_FLOOR && min != max) {
                throw PackMetaException(
                    "A pack that also targets format ${max.major} cannot start below $MULTI_VERSION_FLOOR " +
                        "(1.20): the client refuses it because versions in between would be unable to load it.",
                )
            }
            if (min.major < MULTI_VERSION_FLOOR) {
                throw PackMetaException(
                    "A pack that also targets format ${max.major} cannot start below $MULTI_VERSION_FLOOR " +
                        "(1.20): the client refuses it because versions in between would be unable to load it.",
                )
            }
            val legacyCeiling = minOf(max.major, LAST_PRE_MINOR_FORMAT)
            pack += "pack_format" to Json.Num(min.major)
            pack += "supported_formats" to Json.Arr(listOf(Json.Num(min.major), Json.Num(legacyCeiling)))
            pack += "min_format" to min.toJson()
            pack += "max_format" to max.toJson()
        }

        val root = mutableListOf<Pair<String, Json>>("pack" to Json.Obj(pack))
        if (overlays.isNotEmpty()) {
            val entriesJson = overlays.map { entry -> buildOverlayJson(entry) }
            root += "overlays" to Json.Obj("entries" to Json.Arr(entriesJson))
        }
        return Json.Obj(root)
    }

    private fun buildOverlayJson(entry: OverlayEntry): Json.Obj {
        val fields = mutableListOf<Pair<String, Json>>()
        if (entry.min.major <= LAST_PRE_MINOR_FORMAT) {
            val legacyMax = minOf(entry.max.major, LAST_PRE_MINOR_FORMAT)
            fields += "formats" to Json.Arr(listOf(Json.Num(entry.min.major), Json.Num(legacyMax)))
        }
        fields += "min_format" to entry.min.toJson()
        fields += "max_format" to entry.max.toJson()
        fields += "directory" to Json.Text(entry.directory)
        return Json.Obj(fields)
    }

    fun render(
        description: String,
        min: PackFormat,
        max: PackFormat,
        overlays: List<OverlayEntry> = emptyList(),
    ): String = build(description, min, max, overlays).render() + "\n"
}
