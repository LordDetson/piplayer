package io.github.lorddetson.piplayer.settings.configuration

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.intOrNull

internal fun JsonElement.documentObject(vararg fields: String): JsonObject {
    val value = asObject()
    require(value.keys.all(fields::contains))
    return value
}

internal fun JsonElement.documentString(): String {
    val value = this as? JsonPrimitive ?: errorShape()
    require(value.isString)
    return value.content
}

internal fun JsonElement.documentInt(): Int {
    val value = this as? JsonPrimitive ?: errorShape()
    require(!value.isString && value.content.none { it in ".eE" })
    return value.intOrNull ?: errorShape()
}

internal fun JsonElement.documentBoolean(): Boolean {
    val value = this as? JsonPrimitive ?: errorShape()
    require(!value.isString)
    return when (value.content) {
        "true" -> true
        "false" -> false
        else -> errorShape()
    }
}

internal fun JsonElement.asObject(): JsonObject = this as? JsonObject ?: errorShape()
internal fun errorShape(): Nothing = throw IllegalArgumentException("Invalid configuration JSON")

/** Checks wire types before serialization, which otherwise accepts several quoted scalar types. */
internal fun <T> JsonElement.readUnit(serializer: KSerializer<T>): T =
    Json.decodeFromJsonElement(serializer, normalized(serializer.descriptor))

internal fun <T> writeUnit(serializer: KSerializer<T>, value: T): JsonElement =
    Json.encodeToJsonElement(serializer, value).normalized(serializer.descriptor)

private val float32Decimal = MathContext(9, RoundingMode.HALF_EVEN)

@OptIn(ExperimentalSerializationApi::class)
private fun JsonElement.normalized(type: SerialDescriptor): JsonElement {
    if (this == JsonNull) {
        require(type.isNullable)
        return this
    }
    when (type.kind) {
        StructureKind.CLASS, StructureKind.OBJECT -> return JsonObject(asObject().mapValues { (key, value) ->
            val index = type.getElementIndex(key)
            require(index >= 0)
            value.normalized(type.getElementDescriptor(index))
        }.toSortedMap())
        StructureKind.MAP -> return JsonObject(asObject().mapValues { (key, value) ->
            JsonPrimitive(key).normalized(type.getElementDescriptor(0))
            value.normalized(type.getElementDescriptor(1))
        }.toSortedMap())
        StructureKind.LIST -> {
            val collection = this as? JsonArray ?: errorShape()
            val items = collection.map { it.normalized(type.getElementDescriptor(0)) }
            if (type.serialName.removeSuffix("?").endsWith("Set")) {
                require(items.toSet().size == items.size)
                return JsonArray(items.sortedBy(JsonElement::toString))
            }
            return JsonArray(items)
        }
        PrimitiveKind.STRING -> documentString()
        PrimitiveKind.BOOLEAN -> documentBoolean()
        PrimitiveKind.INT -> documentInt()
        PrimitiveKind.FLOAT -> {
            val value = this as? JsonPrimitive ?: errorShape()
            require(!value.isString)
            val number = value.content.toFloatOrNull()?.takeIf(Float::isFinite) ?: errorShape()
            // A Double represents every Float exactly, and nine decimal digits round-trip every Float.
            // Keep the spelling through the JSON encoder instead of converting it back to a platform-formatted Double.
            val decimal = if (number.toRawBits() == Int.MIN_VALUE) "-0.0"
                else BigDecimal(number.toDouble(), float32Decimal).stripTrailingZeros().toString()
            return JsonUnquotedLiteral(decimal)
        }
        SerialKind.ENUM -> require((0 until type.elementsCount).any { type.getElementName(it) == documentString() })
        else -> errorShape()
    }
    return this
}

/** Bounded reader for the document boundary, including duplicate keys before they can be lost in a map. */
internal class ConfigurationJsonReader(private val source: String) {
    private var offset = 0
    private val encoder = Charsets.UTF_8.newEncoder()

    fun read(): JsonObject {
        val result = value(0).asObject()
        whitespace()
        require(offset == source.length)
        return result
    }

    private fun value(depth: Int): JsonElement {
        require(depth <= MAX_DEPTH)
        whitespace()
        return when (peek()) {
            '{' -> objectValue(depth)
            '[' -> arrayValue(depth)
            '"' -> JsonPrimitive(stringValue())
            else -> literalValue()
        }
    }

    private fun objectValue(depth: Int): JsonObject {
        take('{')
        val members = linkedMapOf<String, JsonElement>()
        if (optional('}')) return JsonObject(members)
        do {
            val key = stringValue()
            require(key !in members)
            take(':')
            members[key] = value(depth + 1)
        } while (optional(','))
        take('}')
        return JsonObject(members)
    }

    private fun arrayValue(depth: Int): JsonArray {
        take('[')
        val elements = mutableListOf<JsonElement>()
        if (optional(']')) return JsonArray(elements)
        do {
            elements += value(depth + 1)
        } while (optional(','))
        take(']')
        return JsonArray(elements)
    }

    private fun stringValue(): String {
        whitespace()
        val start = offset
        require(peek() == '"')
        offset++
        while (offset < source.length) {
            val character = source[offset++]
            require(character >= ' ')
            if (character == '"') {
                return Json.decodeFromString<String>(source.substring(start, offset)).also {
                    require(encoder.canEncode(it))
                }
            }
            if (character == '\\') offset++
        }
        errorShape()
    }

    private fun literalValue(): JsonElement {
        val start = offset
        while (peek()?.let { it !in ",]} \t\r\n" } == true) offset++
        val token = source.substring(start, offset)
        return when (token) {
            "true" -> JsonPrimitive(true)
            "false" -> JsonPrimitive(false)
            "null" -> JsonNull
            else -> {
                require(NUMBER.matches(token))
                Json.parseToJsonElement(token)
            }
        }
    }

    private fun whitespace() {
        while (peek()?.let { it in " \t\r\n" } == true) offset++
    }

    private fun peek(): Char? = source.getOrNull(offset)

    private fun optional(character: Char): Boolean {
        whitespace()
        if (peek() != character) return false
        offset++
        return true
    }

    private fun take(character: Char) {
        require(optional(character))
    }

    private companion object {
        const val MAX_DEPTH = 24
        val NUMBER = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?""")
    }
}
