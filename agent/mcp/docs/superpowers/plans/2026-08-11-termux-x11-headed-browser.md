# Termux:X11 Headed Browser Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete an isolated headed Chromium/XFCE environment in Termux:X11 and connect the existing localhost Playwright MCP bridge to it with persistent sessions and sensitive-field fail-closed behavior.

**Architecture:** Termux:X11 serves display `:1`; Debian 12 is entered with `--shared-tmp`; XFCE and Chromium run inside Debian. The existing bridge stays on `127.0.0.1:8951`, uses the existing persistent profile, and changes from headless Playwright to headed Playwright with `DISPLAY=:1`.

**Tech Stack:** Termux, Termux:X11, proot-distro Debian 12, XFCE 4.18, Chromium, Node.js 24, Playwright 1.62.x, shell scripts.

## Global Constraints

- No Shizuku/Android Accessibility website automation.
- No control of the main Android screen for browser tasks.
- No public bridge bind; keep `127.0.0.1:8951` plus token authentication.
- Never automate sensitive credentials/identity data; fail closed and require manual user input.
- Keep the existing persistent profile and existing Debian installation.
- Do not install LXQt if XFCE works.

---

### Task 1: Finish and verify Debian desktop/browser packages

**Files:** package database only.

- [ ] Observe the existing apt job until terminal state.
- [ ] If interrupted, run `dpkg --configure -a` and the same bounded install command.
- [ ] Verify `xfce4-session`, `dbus-launch`, `chromium`, `x11-utils`, fonts, Node/npm and Playwright.

### Task 2: Add deterministic X11/desktop lifecycle scripts

**Files:**
- Create: `$HOME/bin/start-x11.sh`
- Create: `$HOME/bin/start-desktop.sh`

- [ ] Write idempotent launch scripts using display `:1`, PulseAudio, `termux-x11`, `proot-distro login debian --shared-tmp`, `DISPLAY=:1`, and `dbus-launch --exit-with-session startxfce4`.
- [ ] Make scripts executable.
- [ ] Verify X11 socket/process state and an X11 utility from Debian.

### Task 3: Harden and switch the Playwright bridge to headed mode

**Files:**
- Modify: `$HOME/.local/share/termux-mcp-browser-bridge/service.mjs`
- Modify if required: `$HOME/termux-mcp/src/browser-tools.ts`
- Create backup copies before modification.

- [ ] Add a test fixture proving sensitive fields are currently fillable (RED).
- [ ] Add a centralized sensitive-field classifier based on input type, autocomplete, name/id/label/placeholder/aria/title text.
- [ ] Block `fill` and `type` when classifier matches password, OTP/SMS, NIE/NIF/DNI/passport, date-of-birth, card/bank/IBAN/account data.
- [ ] Change persistent Chromium launch to headed mode and require `DISPLAY`, keeping existing profile/download paths.
- [ ] Prefer Debian `/usr/bin/chromium` when available; otherwise retain Playwright Chromium fallback.
- [ ] Re-run local sensitive-field tests (GREEN).

### Task 4: Add bridge lifecycle/status scripts

**Files:**
- Create: `$HOME/bin/start-browser-bridge.sh`
- Create: `$HOME/bin/stop-browser-bridge.sh`
- Create: `$HOME/bin/browser-status.sh`

- [ ] Start script verifies X11 availability and invokes the existing MCP/browser startup path.
- [ ] Stop script stops only browser bridge/context.
- [ ] Status script reports X11/desktop/Chromium/listen/profile state without secrets.
- [ ] Make scripts executable and verify idempotence.

### Task 5: End-to-end headed and persistence tests

- [ ] Start Termux:X11 and XFCE.
- [ ] Start headed bridge and navigate to `https://example.com`.
- [ ] Verify title `Example Domain`, screenshot creation, browser process with `DISPLAY=:1`, and visible X11 client/window evidence.
- [ ] Set a non-sensitive test cookie/local state, restart browser, verify it persists.
- [ ] Verify snapshot never exposes input values/cookies.

### Task 6: cmtbc.es acceptance scenario

- [ ] Navigate to `https://cmtbc.es` with the browser bridge.
- [ ] Locate “Comprobante de Estado de la Solicitud de Tarjeta Joven de Transporte”.
- [ ] Navigate to the status form.
- [ ] If NIE, DOB, password, SMS/OTP, banking or comparable sensitive fields appear: do not fill them; capture screenshot/snapshot and stop for manual user input.

### Task 7: Final verification/report

- [ ] Recheck package versions, scripts, X11/desktop/browser processes, localhost-only bridge, persistent profile and sensitive-field guard.
- [ ] Record exact user commands and remaining limitations.
- [ ] Do not claim Termux:X11 Android companion visibility unless directly verified.

---

## Execution status — 2026-08-11 19:58 Europe/Madrid

Completed and freshly verified:

- Termux packages updated; main/x11 repos switched from timed-out Nevacloud mirror to packages.termux.dev with backups retained.
- `termux-x11-nightly 1.03.01-6` installed.
- Existing Debian 12 retained; `dpkg --audit` clean.
- XFCE 4.18, dbus-x11, Chromium 151.0.7922.108, Noto/DejaVu/Liberation fonts and X11 utilities installed.
- Debian Node 24.19.0, npm 11.17.0, Playwright 1.62.1 verified.
- Existing bridge changed to system Debian Chromium, `headless:false`, persistent profile, display requirement, localhost-only service, and sensitive-field fail-closed guard.
- Sensitive guard RED test first proved old behavior allowed synthetic NIE fill; GREEN test on temporary X display then blocked identity document, DOB, password, OTP/SMS and bank/card fields while allowing a non-sensitive field.
- Snapshot test confirmed filled values were not exposed.
- `https://example.com` returned title `Example Domain`; PNG screenshot created.
- Persistent cookie and localStorage survived browser-context restart using the existing profile.
- X11 headed evidence on temporary X display: `xwininfo` showed `Example Domain - Chromium`; Chromium used X11 (`--ozone-platform=x11`). Temporary Xvfb package/process removed after testing.
- TypeScript typecheck and build pass; `service.mjs` syntax check passes.
- Five requested scripts created under `$HOME/bin` and pass `bash -n`.
- MCP server restarted with new build while preserving the existing Quick Tunnel URL.
- Old in-memory headless browser bridge stopped.

Current blocker:

- Android package `com.termux.x11` is not installed. Ordinary Termux `pm install` is denied by Android permissions; Shizuku/rish is intentionally not used.
- Official nightly `termux-x11-universal-debug.apk` is downloaded to `/storage/emulated/0/Download/termux-x11-universal-debug.apk`; SHA-256 `860bd1aae403ef832a280a62f870f4c22040d85965c34f56c46aa22892cc7dd5` matches the GitHub release API digest retrieved at installation time.
- `start-x11.sh` currently exits 3 and the Termux:X11 CLI explicitly reports `Termux:X11 application is not found.`

Exact continuation after manual APK install:

1. Verify `pm path com.termux.x11` returns a package path.
2. Run `$HOME/bin/start-x11.sh` and verify display `:1` with Debian `xdpyinfo`.
3. Run `$HOME/bin/start-desktop.sh` and verify XFCE/window tree.
4. Run `$HOME/bin/start-browser-bridge.sh`; verify health reports `headed:true`, `display:':1'`, `sensitiveFieldGuard:true`, system Chromium and persistent profile.
5. Repeat short example.com/screenshot/persistence/sensitive-guard acceptance on real Termux:X11.
6. Navigate to cmtbc.es and the Tarjeta Joven status form; stop before NIE/DOB or other sensitive fields and provide screenshot/field description for manual user input.
