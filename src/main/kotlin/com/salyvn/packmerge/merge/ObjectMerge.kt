package com.salyvn.packmerge.merge

import com.salyvn.packmerge.core.JsonIo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The two shapes that merge without knowing what the file means.
 *
 * `sounds.json` and a translation file are one flat object of independent keys, so the merge is a key
 * union — and the only thing worth reporting is a key two packs define differently, because one of them
 * does not happen. An atlas file is a list under `sources`, and the client applies the sources in order,
 * so concatenation is the merge.
 */
object ObjectMerge {

    /**
     * Union of the objects, later packs last. [level] is how loudly a redefinition is reported: replacing a
     * sound event silently removes somebody's sound, while replacing a translation key is usually the point.
     */
    fun mergeObjects(
        relative: String,
        sources: List<PackSource>,
        report: MergeReport,
        level: IssueLevel,
    ): ByteArray? {
        val merged = linkedMapOf<String, JsonElement>()
        val owner = mutableMapOf<String, String>()
        val replaced = mutableListOf<String>()

        sources.forEach { source ->
            val root = try {
                JsonIo.parse(source.readText(relative)).jsonObject
            } catch (failure: Exception) {
                report.error(relative, "${source.name} does not contain a readable object (${failure.message}); it was left out of the merge.")
                return@forEach
            }
            root.forEach { (key, value) ->
                val previous = merged.put(key, value)
                if (previous != null && previous != value) replaced += "$key (${owner[key]} -> ${source.name})"
                owner[key] = source.name
            }
        }
        if (merged.isEmpty()) return null

        if (replaced.isNotEmpty()) {
            val shown = replaced.take(6).joinToString(", ")
            val rest = if (replaced.size > 6) ", and ${replaced.size - 6} more" else ""
            val message = "${replaced.size} key(s) are defined differently by more than one pack; the later pack's value is the one kept: $shown$rest"
            when (level) {
                IssueLevel.ERROR -> report.error(relative, message)
                IssueLevel.WARNING -> report.warn(relative, message)
                IssueLevel.NOTE -> report.note(relative, message)
            }
        }
        report.note(relative, "${merged.size} key(s) from ${sources.size} packs.")
        return JsonIo.render(JsonObject(merged)).toByteArray()    }

    /** Concatenates the array under [key], which is how atlas sources stack. */
    fun mergeArrayUnder(
        relative: String,
        key: String,
        sources: List<PackSource>,
        report: MergeReport,
    ): ByteArray? {
        val items = mutableListOf<JsonElement>()
        sources.forEach { source ->
            val root = try {
                JsonIo.parse(source.readText(relative)).jsonObject
            } catch (failure: Exception) {
                report.error(relative, "${source.name} is not readable (${failure.message}); it was left out of the merge.")
                return@forEach
            }
            val list = root[key] as? JsonArray
            if (list == null) {
                report.error(relative, "${source.name} has no $key list, so there is nothing of it to merge in.")
                return@forEach
            }
            items.addAll(list)
        }
        if (items.isEmpty()) return null
        report.note(relative, "${items.size} $key entries concatenated from ${sources.size} packs.")
        return JsonIo.render(JsonObject(mapOf(key to JsonArray(items)))).toByteArray()
    }
}
