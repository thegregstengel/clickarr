package net.clickarr.core.common

/**
 * Tiny logging facade so pure-Kotlin modules can log without an Android dependency.
 * The app installs an Android-backed [Sink] at startup; tests get the default no-op or a capturing sink.
 * Every message passes through [Redact] before reaching a sink.
 */
object Log {
    enum class Level { VERBOSE, DEBUG, INFO, WARN, ERROR }

    fun interface Sink {
        fun log(level: Level, tag: String, message: String, throwable: Throwable?)
    }

    @Volatile
    var sink: Sink = Sink { _, _, _, _ -> }

    @Volatile
    var minLevel: Level = Level.DEBUG

    fun v(tag: String, message: () -> String) = emit(Level.VERBOSE, tag, null, message)
    fun d(tag: String, message: () -> String) = emit(Level.DEBUG, tag, null, message)
    fun i(tag: String, message: () -> String) = emit(Level.INFO, tag, null, message)
    fun w(tag: String, throwable: Throwable? = null, message: () -> String) = emit(Level.WARN, tag, throwable, message)
    fun e(tag: String, throwable: Throwable? = null, message: () -> String) = emit(Level.ERROR, tag, throwable, message)

    private inline fun emit(level: Level, tag: String, throwable: Throwable?, message: () -> String) {
        if (level.ordinal < minLevel.ordinal) return
        sink.log(level, tag, Redact.apply(message()), throwable)
    }
}
