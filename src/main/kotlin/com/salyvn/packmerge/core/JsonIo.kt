package com.salyvn.packmerge.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reading side of the JSON handling: other people's files come in through kotlinx.serialization, which is
 * lenient about the trailing commas and unquoted keys real packs contain, and are turned into the [Json]
 * tree this tool writes back out.
 *
 * Every scalar that is not a string becomes [Json.Raw], so a number is written back exactly as it was
 * found. A merge that reformats values it did not have to touch is a merge nobody can review.
 */
object JsonIo {
    val LENIENT: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
        allowComments = true
    }

    fun parse(text: String): JsonElement = LENIENT.parseToJsonElement(text)

    fun convert(element: JsonElement): Json = when (element) {
        is JsonObject -> Json.Obj(element.entries.map { (key, value) -> key to convert(value) })
        is JsonArray -> Json.Arr(element.map { convert(it) })
        JsonNull -> Json.Raw("null")
        is JsonPrimitive -> if (element.isString) Json.Text(element.content) else Json.Raw(element.content)
    }

    /** `element` written with this tool's formatting, with a trailing newline, ready to go on disk. */
    fun render(element: JsonElement): String = convert(element).render() + "\n"
}
