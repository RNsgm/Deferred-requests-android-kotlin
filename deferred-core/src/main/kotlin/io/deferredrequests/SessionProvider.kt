package io.deferredrequests

/**
 * Текущая учётная запись. Нужна для двух вещей:
 *
 * - [owner]: очередь переживает выход из аккаунта, но не смену пользователя — чужие
 *   запросы ушли бы на сервер под текущей учётной записью. При восстановлении очереди
 *   запросы других владельцев удаляются.
 * - [token]: после [Outcome.Unauthorized] очередь стоит, пока токен не сменится на
 *   непустой. Пустой токен означает, что пользователь ещё не вошёл.
 */
public interface SessionProvider {
    public val owner: String?
    public val token: String?

    /** Без учётных записей: владелец не проверяется, после 401 нужен [DeferredQueue.resume]. */
    public object None : SessionProvider {
        override val owner: String? get() = null
        override val token: String? get() = null
    }
}
