package com.salyvn.packmerge.merge

/**
 * How a file has to be combined when two packs both contain it, both at the root (`assets/<ns>/...`) and
 * inside declared `pack.mcmeta` overlay directories (`<overlay>/assets/<ns>/...`).
 */
enum class MergeKind {
    /** Rebuilt from the reconciled version range and overlays rather than merged. */
    PACK_META,

    /** `providers` concatenated, and codepoints checked for collisions across packs. */
    FONT,

    /** `sounds.json`: one object of event definitions. */
    SOUNDS,

    /** A translation file: one object of keys. */
    LANG,

    /** An atlas file: its `sources` list concatenated. */
    ATLAS,

    /** A pre-1.21.4 model file, which is only mergeable when the packs disagree solely about `overrides`. */
    LEGACY_MODEL,

    /** A 1.21.4..26.3 item model definition (`range_dispatch` and/or `select` on `custom_model_data`). */
    ITEM_DEFINITION,

    /** One owner: the last pack to declare it wins, and that is reported as a warning. */
    SINGLE_OWNER,
}

object MergeRules {
    fun of(relative: String, overlayDirs: Set<String> = emptySet()): MergeKind {
        if (relative == "pack.mcmeta") return MergeKind.PACK_META
        val rawParts = relative.split('/')
        val parts = if (rawParts.size >= 4 && rawParts[0] in overlayDirs && rawParts[1] == "assets") {
            rawParts.drop(1)
        } else {
            rawParts
        }
        if (parts.size < 3 || parts[0] != "assets") return MergeKind.SINGLE_OWNER
        val what = parts[2]
        val isJson = relative.endsWith(".json")
        return when {
            what == "font" && isJson -> MergeKind.FONT
            what == "sounds.json" -> MergeKind.SOUNDS
            what == "lang" && isJson -> MergeKind.LANG
            what == "atlases" && isJson -> MergeKind.ATLAS
            what == "models" && isJson -> MergeKind.LEGACY_MODEL
            what == "items" && isJson -> MergeKind.ITEM_DEFINITION
            else -> MergeKind.SINGLE_OWNER
        }
    }
}
