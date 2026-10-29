package sh.pug.gentrack

import org.json.JSONArray
import org.json.JSONObject

private const val PACKAGE = "common.events.v1"
private const val KIND = "[common.events.v1.kind]"
private const val PLATFORMS = "[common.events.v1.platforms]"
private const val PII = "[common.events.v1.pii]"
private const val FIELD_RULES = "[buf.validate.field]"
private const val ANDROID = "PLATFORM_ANDROID"
private const val UNSPECIFIED = "PLATFORM_UNSPECIFIED"

// FileDescriptorProto.message_type, as numbered in a SourceCodeInfo path.
private const val MESSAGE_TYPE = 4

private val SNAKE_CASE = Regex("[a-z][a-z0-9]*(_[a-z0-9]+)*")
private val IDENTIFIER = Regex("[a-z][A-Za-z0-9]*")

private val KOTLIN_KEYWORDS = words(
    """
    as break class continue do else false for fun if in interface is null object package return super this throw
    true try typealias typeof val var when while
    """,
)

// Java code calls the methods too, so neither a Java keyword nor a method of Object can name one.
private val UNUSABLE_METHODS = KOTLIN_KEYWORDS + words(
    """
    abstract assert boolean byte case catch char const default double enum extends final finally float goto
    implements import instanceof int long native new private protected public short static strictfp switch
    synchronized throws transient void volatile clone equals finalize getClass hashCode notify notifyAll toString wait
    """,
)

class CatalogException(message: String) : Exception(message)

private fun fail(message: String): Nothing = throw CatalogException(message)

enum class FieldType(val kotlin: String, val schema: String) {
    STRING("String", "STRING"),
    INT("Int", "INT"),
    LONG("Long", "INT"),
    DOUBLE("Double", "DOUBLE"),
    BOOLEAN("Boolean", "BOOLEAN"),

    // Taken in milliseconds; the SDK sends options.proto's text form, such as "1.5s".
    DURATION("Long", "DURATION"),
}

class Field(val key: String, val type: FieldType, val required: Boolean, val pii: Boolean) {
    val parameter: String = if (type == FieldType.DURATION) "${key}Ms" else key
}

class Event(val kind: String, val comment: List<String>, val fields: List<Field>) {
    val method: String =
        kind.split('_').mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar(Char::uppercaseChar) }
            .joinToString("")
    val constant: String = kind.uppercase()
    val className: String = method.replaceFirstChar(Char::uppercaseChar) + "Event"
}

/**
 * The well-known events for Android in a `buf build` JSON image: messages of `common.events.v1` with a
 * `kind` option, whose `platforms` option is unset or lists Android. Sorted by kind.
 */
fun readEvents(image: JSONObject): List<Event> {
    val events = sortedMapOf<String, Event>()
    for (file in image.getJSONArray("file").objects()) {
        if (file.optString("package") != PACKAGE) continue
        val comments = leadingComments(file)
        file.optJSONArray("messageType")?.objects()?.forEachIndexed { index, message ->
            val event = readEvent(message, comments[listOf(MESSAGE_TYPE, index)]) ?: return@forEachIndexed
            if (events.put(event.kind, event) != null) fail("kind \"${event.kind}\" is declared twice")
        }
    }
    if (events.isEmpty()) fail("no well-known events in the image")
    checkNames(events.values)
    return events.values.toList()
}

private fun readEvent(message: JSONObject, comment: String?): Event? {
    val name = message.getString("name")
    val options = message.optJSONObject("options") ?: return null
    val kind = options.optString(KIND).ifEmpty { return null }
    val platforms = options.optJSONArray(PLATFORMS)?.let { array -> List(array.length()) { array.getString(it) } }
    if (platforms.orEmpty().contains(UNSPECIFIED)) fail("$name lists $UNSPECIFIED in its platforms")
    if (!platforms.isNullOrEmpty() && ANDROID !in platforms) return null
    if (!SNAKE_CASE.matches(kind)) fail("$name: kind \"$kind\" is not snake_case")
    if (message.has("nestedType") || message.has("enumType") || message.has("oneofDecl")) {
        fail("$name: nested types, enums and oneofs are not supported")
    }
    val fields = message.optJSONArray("field")?.objects()?.map { readField(name, it) }.orEmpty()
    return Event(kind, comment?.let(::commentLines).orEmpty(), fields)
}

private fun readField(message: String, field: JSONObject): Field {
    val where = "$message.${field.getString("name")}"
    if (field.optString("label") == "LABEL_REPEATED") fail("$where: repeated and map fields are not supported")
    val type =
        when (val type = field.getString("type")) {
            "TYPE_STRING" -> FieldType.STRING

            "TYPE_INT32", "TYPE_SINT32", "TYPE_SFIXED32" -> FieldType.INT

            "TYPE_INT64", "TYPE_SINT64", "TYPE_SFIXED64", "TYPE_UINT32", "TYPE_FIXED32" -> FieldType.LONG

            "TYPE_DOUBLE", "TYPE_FLOAT" -> FieldType.DOUBLE

            "TYPE_BOOL" -> FieldType.BOOLEAN

            "TYPE_MESSAGE" -> {
                val typeName = field.optString("typeName")
                if (typeName != ".google.protobuf.Duration") fail("$where: $typeName fields are not supported")
                FieldType.DURATION
            }

            else -> fail("$where: $type fields are not supported")
        }
    val options = field.optJSONObject("options")
    val required = options?.optJSONObject(FIELD_RULES)?.optBoolean("required") == true
    val pii = options?.optBoolean(PII) == true
    return Field(field.getString("jsonName"), type, required, pii)
}

private fun checkNames(events: Collection<Event>) {
    val methods = HashSet<String>()
    for (event in events) {
        if (event.method in UNUSABLE_METHODS || !methods.add(event.method)) {
            fail("kind \"${event.kind}\" makes the method name ${event.method}, which is not usable")
        }
        // Names the generated code uses beside the parameters: a parameter would shadow them.
        val parameters = hashSetOf("named", "extras", "options", "extra", "facade", "values")
        for (field in event.fields) {
            val name = field.parameter
            // Every property is also a setter of the event's class, which Java calls.
            if (!IDENTIFIER.matches(name) || name in UNUSABLE_METHODS || !parameters.add(name)) {
                fail("${event.kind}.${field.key} makes the parameter name $name, which is not usable")
            }
        }
    }
}

private fun leadingComments(file: JSONObject): Map<List<Int>, String> {
    val locations = file.optJSONObject("sourceCodeInfo")?.optJSONArray("location") ?: return emptyMap()
    return locations.objects()
        .filter { it.has("path") && it.has("leadingComments") }
        .associate { location ->
            val path = location.getJSONArray("path")
            List(path.length()) { path.getInt(it) } to location.getString("leadingComments")
        }
}

// A comment opening or closing inside KDoc would end it early or swallow the code after it.
private fun commentLines(comment: String): List<String> = comment.lines()
    .map { it.removePrefix(" ").trimEnd().replace("/*", "/&#42;").replace("*/", "&#42;/") }
    .dropWhile { it.isEmpty() }
    .dropLastWhile { it.isEmpty() }

private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

private fun words(text: String): Set<String> = text.split(' ', '\n').filter { it.isNotBlank() }.toSet()
