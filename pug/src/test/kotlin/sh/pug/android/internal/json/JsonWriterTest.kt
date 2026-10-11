package sh.pug.android.internal.json

import kotlin.test.Test
import kotlin.test.assertEquals
import org.json.JSONObject

class JsonWriterTest {
    @Test
    fun `writes nested objects and arrays with separators`() {
        val json =
            JsonWriter()
                .beginObject()
                .name("a")
                .value(1L)
                .name("b")
                .beginArray()
                .value(true)
                .nullValue()
                .beginObject()
                .endObject()
                .beginArray()
                .endArray()
                .value(1.5)
                .endArray()
                .name("c")
                .value("x")
                .endObject()
                .toString()

        assertEquals("""{"a":1,"b":[true,null,{},[],1.5],"c":"x"}""", json)
    }

    @Test
    fun `escapes what JSON requires and keeps the rest`() {
        val tricky = "quote\" backslash\\ newline\n tab\t cr\r bs\b ff\u000C nul\u0000 unit\u001F é 🌍   /"

        val json = JsonWriter().beginObject().name(tricky).value(tricky).endObject().toString()

        assertEquals(
            "{\"quote\\\" backslash\\\\ newline\\n tab\\t cr\\r bs\\b ff\\f nul\\u0000 unit\\u001f é 🌍   /\":" +
                "\"quote\\\" backslash\\\\ newline\\n tab\\t cr\\r bs\\b ff\\f nul\\u0000 unit\\u001f é 🌍   /\"}",
            json,
        )
        val parsed = JSONObject(json)
        assertEquals(tricky, parsed.getString(tricky))
    }

    @Test
    fun `numbers keep their exact value`() {
        val values = listOf(Long.MIN_VALUE, Long.MAX_VALUE, 0L)
        val doubles = listOf(Double.MIN_VALUE, Double.MAX_VALUE, 0.1, -2.5e-300, 1e21)

        val json = JsonWriter().beginArray().apply {
            values.forEach { value(it) }
            doubles.forEach { value(it) }
        }.endArray().toString()

        val parsed = org.json.JSONArray(json)
        values.forEachIndexed { i, expected -> assertEquals(expected, (parsed.get(i) as Number).toLong()) }
        doubles.forEachIndexed { i, expected ->
            assertEquals(expected, (parsed.get(values.size + i) as Number).toDouble())
        }
    }
}
