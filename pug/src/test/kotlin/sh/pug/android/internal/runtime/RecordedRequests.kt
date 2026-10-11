package sh.pug.android.internal.runtime

import com.google.protobuf.DynamicMessage
import com.google.protobuf.Message
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import sh.pug.android.internal.wire.ReferenceMessages

internal fun MockWebServer.takeRequestWithin(seconds: Long = 10): RecordedRequest =
    assertNotNull(takeRequest(seconds, TimeUnit.SECONDS), "no request within $seconds s")

internal fun RecordedRequest.bodyBytes(): ByteArray = assertNotNull(body).toByteArray()

internal fun RecordedRequest.decodeEvents(): List<DynamicMessage> = decodeEvents(bodyBytes())

@Suppress("UNCHECKED_CAST")
internal fun decodeEvents(body: ByteArray): List<DynamicMessage> {
    val batch = DynamicMessage.parseFrom(ReferenceMessages.batchCreateRequest, body)
    return batch.getField(batch.descriptorForType.findFieldByName("events")) as List<DynamicMessage>
}

internal fun DynamicMessage.string(field: String): String = getField(descriptorForType.findFieldByName(field)) as String

@Suppress("UNCHECKED_CAST")
internal fun DynamicMessage.properties(field: String): Map<String, Any> {
    val entries = getField(descriptorForType.findFieldByName(field)) as List<DynamicMessage>
    return entries.associate { entry ->
        val value = entry.getField(entry.descriptorForType.findFieldByName("value")) as DynamicMessage
        entry.string("key") to value.allFields.values.single()
    }
}

internal fun protobufResponse(message: Message): MockResponse = protobufResponse(message.toByteArray())

internal fun protobufResponse(body: ByteArray): MockResponse = MockResponse
    .Builder()
    .code(200)
    .setHeader("Content-Type", "application/proto")
    .body(Buffer().write(body))
    .build()
