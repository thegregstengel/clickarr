package net.clickarr.core.common

/**
 * Typed result for operations that can fail in ways the UI must distinguish
 * ("sign in again" vs "server is off"). Used instead of kotlin.Result so the
 * failure side carries a [ClickarrError] rather than an arbitrary Throwable.
 */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: ClickarrError) : Outcome<Nothing>

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): T? = (this as? Success)?.value

    fun errorOrNull(): ClickarrError? = (this as? Failure)?.error

    fun <R> map(transform: (T) -> R): Outcome<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    companion object {
        fun <T> success(value: T): Outcome<T> = Success(value)
        fun failure(error: ClickarrError): Outcome<Nothing> = Failure(error)
    }
}

inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
}

inline fun <T> Outcome<T>.getOrElse(onFailure: (ClickarrError) -> T): T = when (this) {
    is Outcome.Success -> value
    is Outcome.Failure -> onFailure(error)
}

/** Failure categories shared across providers, household, and playback. */
sealed interface ClickarrError {
    val message: String
    val cause: Throwable?

    data class Unauthorized(override val message: String = "Not authorized", override val cause: Throwable? = null) : ClickarrError
    data class Unreachable(override val message: String = "Unreachable", override val cause: Throwable? = null) : ClickarrError
    data class NotFound(override val message: String = "Not found", override val cause: Throwable? = null) : ClickarrError
    data class Unsupported(override val message: String = "Unsupported", override val cause: Throwable? = null) : ClickarrError
    data class Invalid(override val message: String, override val cause: Throwable? = null) : ClickarrError
    data class Unknown(override val message: String = "Unknown error", override val cause: Throwable? = null) : ClickarrError
}
