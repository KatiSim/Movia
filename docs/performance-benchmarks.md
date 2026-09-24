# Movia performance benchmarks

The `macrobenchmark` module measures cold startup to first draw and full draw, primary-tab navigation, frame timing, and runtime memory. It targets the minified `benchmark` app variant. Macrobenchmark results include startup median and frame-duration percentiles, including p50 and p95. Home reports fully drawn after a cached feed, a completed first refresh, or a visible refresh error; the transient `isRefreshing` flag alone does not complete startup timing.

## Run on a physical Android device

```sh
./gradlew :macrobenchmark:connectedBenchmarkReleaseAndroidTest
./gradlew :app:generateBaselineProfile
```

Use a release-like build on the same device and API level for before/after comparisons. Keep the backend and network conditions the same for both runs. The catalog search/filter/details/player journey requires an available catalog card; it is skipped when the backend and local cache contain no items. The tab and cold-start journeys still run without catalog data.

`MemoryUsageMetric` reports RSS, heap, and GPU submetrics. To compare the requested PSS after one and two full app tours, run the same UI journey and capture:

```sh
adb shell dumpsys meminfo app.movia.android
```

Capture both snapshots after the app has settled, and record device model, Android version, build variant, and whether the backend was online. Macrobenchmark artifacts and trace files are written under `macrobenchmark/build/outputs/connected_android_test_additional_output/`.

Debug builds enable `StrictMode` network detection. Inspect `StrictMode` entries in logcat while exercising screens to catch accidental main-thread networking. Perfetto traces include `Movia.Home.refresh`, `Movia.Catalog.page`, `Movia.Details.bundle`, and asynchronous `Movia.Artwork` spans for poster and ambient image requests on Android 10 and later.
