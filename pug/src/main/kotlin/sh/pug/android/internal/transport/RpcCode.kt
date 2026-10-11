package sh.pug.android.internal.transport

internal enum class RpcCode(val wireName: String) {
    CANCELED("canceled"),
    UNKNOWN("unknown"),
    INVALID_ARGUMENT("invalid_argument"),
    DEADLINE_EXCEEDED("deadline_exceeded"),
    NOT_FOUND("not_found"),
    ALREADY_EXISTS("already_exists"),
    PERMISSION_DENIED("permission_denied"),
    RESOURCE_EXHAUSTED("resource_exhausted"),
    FAILED_PRECONDITION("failed_precondition"),
    ABORTED("aborted"),
    OUT_OF_RANGE("out_of_range"),
    UNIMPLEMENTED("unimplemented"),
    INTERNAL("internal"),
    UNAVAILABLE("unavailable"),
    DATA_LOSS("data_loss"),
    UNAUTHENTICATED("unauthenticated"),
    ;

    /** Codes a retry cannot fix, so the batch is dropped instead of rolled back (sdk-web `PERMANENT_GRPC_CODES`). */
    val isPermanent: Boolean
        get() =
            when (this) {
                INVALID_ARGUMENT,
                NOT_FOUND,
                ALREADY_EXISTS,
                PERMISSION_DENIED,
                FAILED_PRECONDITION,
                UNIMPLEMENTED,
                UNAUTHENTICATED,
                -> true

                else -> false
            }

    companion object {
        fun fromWireName(name: String): RpcCode? = entries.firstOrNull { it.wireName == name }

        // Fallback for an error body that is not Connect JSON, i.e. a proxy, CDN or WAF page.
        // Matches sdk-web's rpc.ts: keeping unknown 4xx permanent stops a 413 from retrying forever.
        fun fromHttpStatus(status: Int): RpcCode = when (status) {
            401 -> UNAUTHENTICATED
            403 -> PERMISSION_DENIED
            404 -> UNIMPLEMENTED
            408, 429 -> UNAVAILABLE
            in 400..499 -> INVALID_ARGUMENT
            else -> UNAVAILABLE
        }
    }
}

internal class PugTransportException(
    val code: RpcCode,
    message: String,
    cause: Throwable? = null,
    val isPermanent: Boolean = code.isPermanent,
) : Exception(message, cause)
