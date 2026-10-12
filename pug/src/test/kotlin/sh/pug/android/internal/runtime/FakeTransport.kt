package sh.pug.android.internal.runtime

import kotlinx.coroutines.CompletableDeferred
import sh.pug.android.internal.transport.PugTransport
import sh.pug.android.internal.transport.PugTransportException
import sh.pug.android.internal.transport.RpcCode
import sh.pug.android.internal.wire.BatchCreateResponse
import sh.pug.android.internal.wire.Event
import sh.pug.android.internal.wire.IdentifyRequest

/**
 * Records every call and answers from a script, accepting everything by default. After [hold], calls
 * wait until released, which opens the window between a request and its answer.
 */
internal class FakeTransport(private val now: () -> Long = { 0L }) : PugTransport {
    val batches = mutableListOf<List<Event>>()
    val batchTimes = mutableListOf<Long>()
    val identifies = mutableListOf<IdentifyRequest>()

    /** While set, every batch fails with this code. */
    var batchFailure: RpcCode? = null

    private val batchAnswers = ArrayDeque<() -> BatchCreateResponse?>()
    private val identifyAnswers = ArrayDeque<() -> Unit>()
    private var holding = false
    private val held = mutableListOf<CompletableDeferred<Unit>>()

    val kinds: List<List<String>> get() = batches.map { batch -> batch.map { it.kind } }

    fun failNextBatch(code: RpcCode) {
        batchAnswers.addLast { throw failure(code) }
    }

    fun answerNextBatch(response: BatchCreateResponse?) {
        batchAnswers.addLast { response }
    }

    fun failNextIdentify(code: RpcCode) {
        identifyAnswers.addLast { throw failure(code) }
    }

    fun hold() {
        holding = true
    }

    /** Lets the [index]th held call, in call order, finish. */
    fun release(index: Int) {
        held[index].complete(Unit)
    }

    fun releaseAll() {
        holding = false
        held.forEach { it.complete(Unit) }
    }

    override suspend fun sendBatch(events: List<Event>): BatchCreateResponse? {
        batches += events
        batchTimes += now()
        awaitRelease()
        batchFailure?.let { throw failure(it) }
        val answer = batchAnswers.removeFirstOrNull() ?: return BatchCreateResponse(events.size.toLong(), emptyMap())
        return answer()
    }

    override suspend fun identify(request: IdentifyRequest) {
        identifies += request
        awaitRelease()
        identifyAnswers.removeFirstOrNull()?.invoke()
    }

    private suspend fun awaitRelease() {
        if (!holding) return
        val gate = CompletableDeferred<Unit>()
        held += gate
        gate.await()
    }

    private fun failure(code: RpcCode) = PugTransportException(code, "scripted")
}
