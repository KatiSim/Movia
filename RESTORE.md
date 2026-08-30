# Movia restore procedure

This file is the recovery entry point. It describes how to restore the current
baseline without relying on the memory of the previous agent.

## 1. Clone the repository

    git clone https://github.com/KatiSim/Movia.git
    cd Movia
    git checkout main

For a reproducible checkpoint, checkout the current movia-baseline-YYYYMMDD tag
or the current baseline tag listed in the GitHub Release.

## 2. Install Termux requirements

Install Termux packages:

    pkg update
    pkg install git python nodejs openjdk-17 curl openssl

Install Python dependencies:

    python -m pip install -r backend/requirements.txt

Install/build MCP dependencies:

    cd agent/mcp
    npm ci
    npm run build
    cd ../..

Android SDK/command-line tools and an Android SDK platform/build-tools version
compatible with the Gradle files under android/ are also required. Do not commit
android/local.properties; create it locally with the SDK path.

## 3. Restore backend

The repository contains the backend source, tests, service definitions and
catalog schema. It intentionally does not contain the live multi-hundred-
megabyte database, WAL, runtime caches or secrets.

The observed current SSOT is:

    /data/data/com.termux/files/home/projects/media-parser/catalog.db

To restore the catalog, use a verified GitHub Release catalog snapshot when one
is attached, or rebuild it from the schema and discovery code:

    python backend/restore_all.py

Before replacing a live DB, stop writers and validate the snapshot checksum from
the release SHA256SUMS.txt. If no snapshot is attached, run the documented
discovery/import pipeline and record the resulting DB path and checksum in
database/CATALOG_DB_STATUS.md.

## 4. Configure services

Copy or install the service definitions from agent/services/ into the Termux
runit service directory:

    mkdir -p "$PREFIX/var/service"
    cp -a agent/services/movia-media-parser "$PREFIX/var/service/"
    cp -a agent/services/movia-stream-enricher "$PREFIX/var/service/"
    cp -a agent/services/movia-stream-enricher-log "$PREFIX/var/service/"
    cp -a agent/services/movia-cache-pruner "$PREFIX/var/service/"

The definitions expect the backend checkout at $HOME/projects/media-parser.
Either restore the backend there or adapt the working directory locally; do
not put the DB into Git.

Start/check services with sv up <service> and sv status <service>. The MCP
process is started by bash agent/mcp/start.sh after npm ci and npm run build.

## 5. Configure secrets

Read SECRETS_SETUP.md. Copy examples and fill them only in Termux/private
configuration locations. Never put values in this repository, GitHub Issues,
logs or release assets.

The backend reads environment values through backend/config.py. The MCP server
reads TERMUX_MCP_SECRET and related values through agent/mcp/src/config.ts. The
native Android agent token is provisioned by
agent/tools/provision-agent-token.sh into the private agent config area.

## 6. Build and install APK

    bash scripts/build.sh
    bash scripts/install.sh --apk android/app/build/outputs/apk/debug/app-debug.apk

Install with replacement using adb install -r and verify app.movia.android,
versionName and versionCode. Do not clear app data as a restore step.

## 7. Provision agent

After the APK is installed and the private token is available:

    agent/tools/provision-agent-token.sh

The value is supplied interactively or via the private environment expected by
that script. The value must not be written to Git-tracked files.

## 8. Start services

    sv up movia-media-parser
    sv up movia-stream-enricher
    sv up movia-stream-enricher-log
    sv up movia-cache-pruner
    bash agent/mcp/start.sh

## 9. Verify health

    bash scripts/health-check.sh
    bash scripts/verify-project.sh
    bash scripts/restore-check.sh

A health failure is a real failure to resolve, not a reason to write PASS into
the manifest.

## 10. Verify MCP

Confirm the MCP process is listening on the configured local port, then run the
current MCP smoke/acceptance script and verify the native Movia tool inventory
contains the 29 source-registered tools. The MCP endpoint is HTTP POST /mcp; a
GET request may return 404/405 and is not by itself a protocol failure.

## 11. Verify installed version

    adb shell dumpsys package app.movia.android | grep -E 'versionCode|versionName'
    adb shell pm path app.movia.android

The current phone baseline is package app.movia.android, versionName 0.9.23,
versionCode 293.
