package app.movia.android.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.uiAutomator
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET_PACKAGE = "app.movia.android"

@RunWith(AndroidJUnit4::class)
class MoviaBaselineProfile {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startupAndPrimaryNavigation() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
        profileBlock = {
            uiAutomator {
                startApp(TARGET_PACKAGE)
                device.tapMoviaTab(1)
                device.tapMoviaTab(2)
                device.tapMoviaTab(0)
            }
        },
    )
}
