package com.salyvn.packmerge.merge

enum class IssueLevel { ERROR, WARNING, NOTE }

enum class MergeRule(
    val id: String,
    val ruleName: String,
    val defaultLevel: IssueLevel,
    val shortDescription: String,
    val fullDescription: String,
) {
    FONT_CODEPOINT_COLLISION(
        id = "PM001",
        ruleName = "FontCodepointCollision",
        defaultLevel = IssueLevel.ERROR,
        shortDescription = "Font codepoint claimed by multiple resource packs",
        fullDescription = "Multiple input resource packs assign a glyph or advance to the same Unicode codepoint in the same font file, leaving earlier glyphs unreachable.",
    ),
    LEGACY_OVERRIDE_COLLISION(
        id = "PM002",
        ruleName = "LegacyOverrideCollision",
        defaultLevel = IssueLevel.ERROR,
        shortDescription = "Legacy item model override predicate collision",
        fullDescription = "Multiple input resource packs define identical model override predicates on the same legacy item model, causing earlier items to be shadowed.",
    ),
    ITEM_DEFINITION_COLLISION(
        id = "PM003",
        ruleName = "ItemDefinitionCollision",
        defaultLevel = IssueLevel.ERROR,
        shortDescription = "Item definition custom_model_data dispatch collision",
        fullDescription = "Multiple input resource packs claim the same custom_model_data float threshold or select case key on the same 1.21.4+ item model definition.",
    ),
    UNMERGEABLE_ASSET_JSON(
        id = "PM004",
        ruleName = "UnmergeableAssetJson",
        defaultLevel = IssueLevel.ERROR,
        shortDescription = "Malformed or structurally incompatible JSON asset",
        fullDescription = "An asset file could not be parsed as valid JSON or uses a root structure that cannot be safely unioned across packs.",
    ),
    INCOMPATIBLE_PACK_FORMATS(
        id = "PM005",
        ruleName = "IncompatiblePackFormats",
        defaultLevel = IssueLevel.ERROR,
        shortDescription = "Input resource packs declare disjoint pack format ranges",
        fullDescription = "The highest minimum format required among input packs exceeds the lowest maximum format ceiling, leaving no shared Minecraft version.",
    ),
    SINGLE_OWNER_OVERWRITE(
        id = "PM006",
        ruleName = "SingleOwnerOverwrite",
        defaultLevel = IssueLevel.WARNING,
        shortDescription = "Single-owner asset overwritten by higher-priority pack",
        fullDescription = "Multiple input packs ship non-identical bytes for a file that cannot be stacked (such as a texture, shader, or OGG sound); only the last pack's copy is kept.",
    ),
    SOUND_EVENT_OVERWRITE(
        id = "PM007",
        ruleName = "SoundEventOverwrite",
        defaultLevel = IssueLevel.WARNING,
        shortDescription = "Sound event key redefined across packs",
        fullDescription = "Multiple input packs define different sound event configurations for the same key in sounds.json; the higher-priority pack replaces earlier definitions.",
    ),
    MODEL_PROPERTY_CONFLICT(
        id = "PM008",
        ruleName = "ModelPropertyConflict",
        defaultLevel = IssueLevel.WARNING,
        shortDescription = "Item model base or dispatch property disagreement",
        fullDescription = "Input packs disagree on base model fields (parent, textures, scale, index, fallback, hand_animation_on_swap, or oversized_in_gui) while sharing dispatch tables.",
    ),
    INVALID_OVERLAY_DIRECTORY(
        id = "PM009",
        ruleName = "InvalidOverlayDirectory",
        defaultLevel = IssueLevel.ERROR,
        shortDescription = "Invalid overlay directory identifier in pack.mcmeta",
        fullDescription = "An entry in pack.mcmeta overlays.entries uses characters outside [a-z0-9_-], which the Minecraft client rejects.",
    ),
    OVERLAY_FORMAT_CONFLICT(
        id = "PM010",
        ruleName = "OverlayFormatConflict",
        defaultLevel = IssueLevel.WARNING,
        shortDescription = "Conflicting format bounds for shared overlay directory",
        fullDescription = "Multiple packs declare the same overlay directory in pack.mcmeta with non-overlapping format ranges.",
    ),
    ADDITIVE_MERGE_SUMMARY(
        id = "PM011",
        ruleName = "AdditiveMergeSummary",
        defaultLevel = IssueLevel.NOTE,
        shortDescription = "Additive resource merge summary",
        fullDescription = "Informational summary of providers, overrides, custom_model_data entries, select cases, atlas sources, or translation keys combined across packs.",
    ),
    PACK_FORMAT_NOTE(
        id = "PM012",
        ruleName = "PackFormatNote",
        defaultLevel = IssueLevel.NOTE,
        shortDescription = "Pack format reconciliation or coverage note",
        fullDescription = "Informational note explaining pack.mcmeta floor/ceiling reconciliation or font providers whose codepoint coverage is resolved at client runtime.",
    ),
}

/** Something worth telling the person running the merge, with the file and diagnostic rule it belongs to. */
data class Issue(
    val level: IssueLevel,
    val path: String,
    val message: String,
    val rule: MergeRule = defaultRuleFor(level),
) {
    companion object {
        fun defaultRuleFor(level: IssueLevel): MergeRule = when (level) {
            IssueLevel.ERROR -> MergeRule.UNMERGEABLE_ASSET_JSON
            IssueLevel.WARNING -> MergeRule.SINGLE_OWNER_OVERWRITE
            IssueLevel.NOTE -> MergeRule.ADDITIVE_MERGE_SUMMARY
        }
    }
}

/**
 * One file the merge would write.
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

    fun error(path: String, message: String, rule: MergeRule = MergeRule.UNMERGEABLE_ASSET_JSON) =
        add(IssueLevel.ERROR, path, message, rule)

    fun warn(path: String, message: String, rule: MergeRule = MergeRule.SINGLE_OWNER_OVERWRITE) =
        add(IssueLevel.WARNING, path, message, rule)

    fun note(path: String, message: String, rule: MergeRule = MergeRule.ADDITIVE_MERGE_SUMMARY) =
        add(IssueLevel.NOTE, path, message, rule)

    private fun add(level: IssueLevel, path: String, message: String, rule: MergeRule) {
        collected += Issue(level, path, message, rule)
    }

    /** Errors first, then warnings, then notes, each group in the order the files were visited. */
    val issues: List<Issue> get() = collected.sortedBy { it.level.ordinal }

    val errors: Int get() = collected.count { it.level == IssueLevel.ERROR }

    val warnings: Int get() = collected.count { it.level == IssueLevel.WARNING }

    val notes: Int get() = collected.count { it.level == IssueLevel.NOTE }
}
