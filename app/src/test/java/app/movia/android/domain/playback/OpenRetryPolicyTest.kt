package app.movia.android.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenRetryPolicyTest {
    @Test
    fun retriesExactlyOnceAfterFirstOpenException() {
        var opens = 0
        var resets = 0
        val result = openWithSingleRetry(
            resetBeforeRetry = { resets += 1 },
            open = {
                opens += 1
                if (opens == 1) throw IllegalStateException("first")
                42L
            },
        )
        assertEquals(42L, result)
        assertEquals(2, opens)
        assertEquals(1, resets)
    }

    @Test
    fun secondOpenExceptionEscapesWithoutThirdAttempt() {
        var opens = 0
        var resets = 0
        assertThrows(IllegalStateException::class.java) {
            openWithSingleRetry(
                resetBeforeRetry = { resets += 1 },
                open = {
                    opens += 1
                    throw IllegalStateException("fail-$opens")
                },
            )
        }
        assertEquals(2, opens)
        assertEquals(1, resets)
    }
}
