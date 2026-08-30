# Acceptance and regression tests

Android acceptance tests are exposed through the tracked pointer:

~~~text
acceptance/androidTest -> ../android/app/src/androidTest
~~~

The current source repository contains navigation/player-related Android tests. External backend, stream-resolution and full playback acceptance are not PASS on this checkpoint because the current backend/APK/runtime were not found on the checked phone.

Future acceptance must record exact source commit, APK checksum, backend/agent health, media identity, selected quality/voice, active streamId/URL, fallback behavior and playback duration.
