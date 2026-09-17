# Movia restore procedure — canonical 0.9.32

## 1. Clone and checkout the canonical version

```bash
git clone https://github.com/KatiSim/movia.git
cd movia
git checkout v0.9.32
```

## 2. Local Android configuration

Create `local.properties` with the local Android SDK path. Do not commit it. Runtime credentials and
agent tokens remain private and must never be added to Git.

## 3. Build

```bash
./gradlew :app:assembleDebug --no-daemon
```

Expected output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 4. Install without clearing data

```bash
APK=app/build/outputs/apk/debug/app-debug.apk
cat "$APK" | rish -c 'cat > /data/local/tmp/movia-debug.apk'
rish -c 'pm install -r -d /data/local/tmp/movia-debug.apk'
rish -c 'rm -f /data/local/tmp/movia-debug.apk'
```

Do not use `pm clear` as part of restoration.

## 5. Verify the exact installed artifact

```bash
LOCAL=$(sha256sum app/build/outputs/apk/debug/app-debug.apk | awk '{print $1}')
INST=$(rish -c "sha256sum \$(pm path app.movia.android | sed 's/package://')" 2>&1 | awk '{print $1}' | tail -1)
printf 'LOCAL=%s\nINSTALLED=%s\n' "$LOCAL" "$INST"
test "$LOCAL" = "$INST"
```

For the phone baseline captured on 2026-09-17, the verified APK SHA-256 was:

`8c608bf16f5798b388200e9b4334c249ae8e751765ee66063559632021d9d4c0`

A rebuild may have a different byte hash if the build environment changes; the required invariant is
that the APK being installed and the installed `base.apk` match each other.

## 6. Launch

```bash
rish -c 'am force-stop app.movia.android; am start -W -n app.movia.android/.MainActivity'
```

## 7. Recovery rule

`v0.9.32` is the canonical reference. Do not restore old `.bak` snapshots or old APKs over this tree.
Use Git history only for targeted forensic comparison and reapply changes deliberately.
