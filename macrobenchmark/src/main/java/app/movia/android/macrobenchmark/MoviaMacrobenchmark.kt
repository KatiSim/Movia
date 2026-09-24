package app.movia.android.macrobenchmark

import android.os.SystemClock
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

private const val TARGET_PACKAGE = "app.movia.android"

@RunWith(AndroidJUnit4::class)
class MoviaMacrobenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartupToHome() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        iterations = 10,
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun primaryTabJourneyAndMemory() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            MemoryUsageMetric(
                mode = MemoryUsageMetric.Mode.Last,
                subMetrics = MemoryUsageMetric.SubMetric.entries,
                processNameSuffix = "",
                metricNameSuffix = "movia",
            ),
        ),
        compilationMode = CompilationMode.None(),
        iterations = 10,
        startupMode = StartupMode.WARM,
        setupBlock = { startActivityAndWait() },
    ) {
        device.waitForIdle()
        device.tapMoviaTab(1)
        device.tapMoviaTab(2)
        device.tapMoviaTab(0)
        device.tapMoviaTab(1)
        device.tapMoviaTab(0)
    }

    @Test
    fun catalogSearchFilterDetailsPlayerJourneyWhenContentIsAvailable() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.None(),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = { startActivityAndWait() },
    ) {
        device.tapMoviaTab(1)

        val searchField = device.requireMoviaObject("catalog.search")
        searchField.click()
        searchField.setText("movia benchmark query")
        device.pressEnter()
        device.waitForIdle()
        // Catalog search is debounced in the ViewModel; allow its request to start.
        SystemClock.sleep(400L)

        device.requireMoviaObject("catalog.filter").click()
        device.requireMoviaObject("catalog.filter.sheet")
        device.requireMoviaObject("catalog.filter.apply").click()
        device.waitForIdle()

        device.requireMoviaObject("catalog.search.clear").click()
        val firstCard: UiObject2? = device.wait(
            Until.findObject(
                By.res(Pattern.compile(".*catalog\\.item\\.open\\..*"))
                    .pkg(TARGET_PACKAGE),
            ),
            10_000L,
        )
        assumeTrue(
            "Catalog has no available content; Details and Player benchmark requires an item.",
            firstCard != null,
        )

        checkNotNull(firstCard).click()
        device.requireMoviaObject("details_play", timeoutMs = 10_000L).click()
        val player: UiObject2? = device.wait(
            Until.findObject(By.res(TARGET_PACKAGE, "player_screen")),
            10_000L,
        )
        assumeTrue("Playback could not be opened for the selected catalog item.", player != null)
        device.requireMoviaObject("player.seekForward", timeoutMs = 5_000L).click()
        device.requireMoviaObject("player.back").click()
        device.pressBack()
        device.tapMoviaTab(2)
        device.tapMoviaTab(0)
    }
}
