package app.movia.android.data.download

import app.movia.android.domain.model.MediaRef

/** Title-only artifacts are unbound until migration proves their catalog identity. */
internal object OfflineLookupPolicy {
    fun <T> find(request: MediaRef?, exact: () -> T?, legacy: () -> T?): T? {
        val current = exact()
        if (current != null || request != null) return current
        return legacy()
    }

    fun <T> candidates(request: MediaRef?, exact: T, legacy: T): Set<T> =
        if (request != null) setOf(exact) else setOf(exact, legacy)
}
