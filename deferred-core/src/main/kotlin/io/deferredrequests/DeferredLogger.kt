package io.deferredrequests

/** Журнал очереди. По умолчанию молчит. */
public interface DeferredLogger {
    public fun debug(message: String) {}
    public fun warn(message: String) {}
    public fun error(message: String, error: Throwable? = null) {}

    public object None : DeferredLogger
}
