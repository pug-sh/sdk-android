package sh.pug.android.internal.transport

import sh.pug.android.internal.wire.BatchCreateResponse
import sh.pug.android.internal.wire.Event
import sh.pug.android.internal.wire.IdentifyRequest

internal interface PugTransport {
    /**
     * Returns the server's acknowledgement, or null when a 2xx body could not be decoded (the
     * request was still accepted). Throws [PugTransportException] for every failure.
     */
    suspend fun sendBatch(events: List<Event>): BatchCreateResponse?

    suspend fun identify(request: IdentifyRequest)
}
