package com.salyvn.packmerge.merge

/**
 * How a file has to be combined when two packs both contain it.
 *
 * The distinction the client itself makes is between files that *stack* and files that *replace*. A font id
 * is loaded from every pack and its providers concatenated; a model file has exactly one owner and the pack
 * higher in the list wins. Copying one directory over another treats everything as the second kind, which
 * is why merging by hand loses one plugin's fonts, sounds or items and does it quietly.
 */
enum class MergeKind {
    /** Rebuilt from the reconciled version range rather than merged. */
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

    /** An item model definition, mergeable when both are a `custom_model_data` dispatch. */
    ITEM_DEFINITION,

    /** One owner: the last pack to declare it wins, and that is worth saying out loud. */
    SINGLE_OWNER,
}

object MergeRules {
    fun of(relative: String): MergeKind {
        if (relative == "pack.mcmeta") return MergeKind.PACK_META
        val parts = relative.split('/')
        // Everything below is `assets/<namespace>/<what>/...`.
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
