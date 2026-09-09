# Movia 0.9.32 restore procedure

This is the single recovery entry point for the canonical `0.9.32 / 302` baseline.

## A. App-only restore after uninstall

If Termux/backend still exists and only the Android app was deleted, no rebuild is required.

```bash
git clone https://github.com/KatiSim/Movia.git
cd Movia
git checkout main
sha256sum -c release/SHA256SUMS.txt
bash scripts/install.sh
```

Expected APK SHA-256:

`25e9c2a3a49e4649376b871f469bec3df39160c7ef86743d317a41855f23f49b`

The install script uses Shizuku/rish when available and otherwise adb.

**Android uninstall removes private app data.** The APK/source cannot recreate deleted history, My List, DataStore preferences or private download records unless those were backed up separately.

## B. Clean Termux/device restore

### 1. Clone

```bash
git clone https://github.com/KatiSim/Movia.git
cd Movia
git checkout main
bash scripts/restore-check.sh
```

For an immutable checkpoint use tag `v0.9.32` after it is published.

### 2. Install Termux packages

A captured working environment used Python 3.14.6, Node 24.18.0, npm 11.19.1, JDK 21.0.12 and aria2 1.37.0. The project itself targets Java 17 bytecode.

```bash
pkg update
pkg install git python nodejs openjdk-21 curl openssl aria2 termux-services
python3 -m pip install -r backend/requirements.txt
cd agent/mcp
npm ci
npm run build
cd ../..
```

Android builds additionally require Android SDK platform 35. Set:

```bash
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
```

### 3. Private configuration

Copy only variable names from `.env.example`; provide values privately. Read `SECRETS_SETUP.md`.

Typical backend file:

```text
$HOME/projects/media-parser/.env
```

At minimum TMDB enrichment requires a private TMDB credential. Never commit `.env`, tokens, cookies, keystores or GitHub credentials.

### 4. Install local runtime and P2P services

```bash
bash scripts/setup-local-runtime.sh
```

This script:

- creates `$HOME/projects/media-parser` as a link to this checkout's `backend/` when no runtime exists;
- creates the Movia aria2 loopback configuration when absent;
- downloads official TorrServer `MatriX.144.1` Android ARM64;
- verifies SHA-256 `bb7e9b4d0dc894f8da3e32496e7487be93b8f8b04ada549396a7ab4dc85ea63b`;
- installs the tracked runit service definitions.

Then:

```bash
sv up movia-torrserver
sv up movia-media-parser
sv up movia-stream-enricher
sv up movia-stream-enricher-log
sv up movia-cache-pruner
```

The media-parser service ensures localhost aria2 RPC is started before `streamer.py`.

### 5. Catalog

Current runtime catalog is mutable and intentionally excluded from Git. The recovery capture observed:

- 71,899 movie rows
- schema 4
- catalog revision 9718
- normalization 1
- `PRAGMA quick_check = ok`

If the old Termux runtime survived, keep `$HOME/projects/media-parser/catalog.db`.

For an exact catalog snapshot exported earlier:

```bash
bash scripts/import-runtime-catalog.sh ~/Movia-catalog-YYYYMMDD_HHMMSS.sqlite.gz
```

To make a future SQLite-consistent backup:

```bash
bash scripts/export-runtime-catalog.sh
```

Without a snapshot, rebuild/populate the catalog using the backend schema/sync/enrichment tools and private provider/TMDB configuration. See `database/CATALOG_DB_STATUS.md` and `backend/README.md`.

### 6. Development signing key

The exact tracked APK is already signed, so an app-only reinstall after uninstall does not require creating a key.

To build new APKs:

```bash
bash scripts/bootstrap-debug-keystore.sh
```

A new debug key is acceptable after a complete uninstall. It cannot update an already-installed package signed with another key.

### 7. Install exact canonical APK

```bash
bash scripts/install.sh
```

Verify:

```bash
bash scripts/health-check.sh --package
```

Expected: `app.movia.android`, `0.9.32`, code `302`.

### 8. Rebuild from source when needed

```bash
bash scripts/build.sh
```

Output:

`android/app/build/outputs/apk/debug/app-debug.apk`

The canonical checked-in APK is not overwritten by this command. A rebuilt APK may have a different byte hash due to build/signing environment even when behavior/source is equivalent.

### 9. Start MCP / native agent

```bash
bash agent/mcp/start.sh
```

The native Android agent token is provisioned privately with `agent/tools/provision-agent-token.sh`. Current source registers 30 Movia MCP tools.

### 10. Verify everything

```bash
bash scripts/restore-check.sh
bash scripts/verify-project.sh
bash scripts/health-check.sh --full --package
```

Expected localhost services:

- control plane: `127.0.0.1:8888`
- TorrServer: `127.0.0.1:18090`
- aria2 RPC: `127.0.0.1:6800`
- MCP health: `127.0.0.1:8940/healthz`

## C. Visual and behavior reconstruction

If UI code ever has to be reconstructed rather than merely rebuilt, use these as the canonical specification:

- `docs/DESIGN_SYSTEM_0.9.32.md`
- `docs/INTERACTION_LOGIC_0.9.32.md`

They document palette, launcher/logo, bottom glass bar, gold glow/outlines, cards, metadata, Details, people, player, notification, season sheets, gestures and button state logic.
