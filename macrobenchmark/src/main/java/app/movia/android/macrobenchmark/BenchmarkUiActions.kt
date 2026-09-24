package app.movia.android.macrobenchmark

import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

private const val MOVIA_PACKAGE = "app.movia.android"

internal fun UiDevice.requireMoviaObject(resourceName: String, timeoutMs: Long = 5_000L): UiObject2 =
    checkNotNull(wait(Until.findObject(By.res(MOVIA_PACKAGE, resourceName)), timeoutMs)) {
        "Timed out waiting for $MOVIA_PACKAGE:id/$resourceName"
    }

internal fun UiDevice.tapMoviaTab(index: Int) {
    val tag = when (index) {
        0 -> "navigation.home"
        1 -> "navigation.catalog"
        2 -> "navigation.library"
        else -> error("Unknown Movia top-level destination index: $index")
    }
    val button = requireMoviaObject(tag)
    button.click()
    waitForIdle()
}
