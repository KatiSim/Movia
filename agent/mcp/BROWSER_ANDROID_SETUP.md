# Termux MCP — Browser + Android UI setup

Date: 2026-08-01

## Architecture

```text
ChatGPT
  -> existing public Kati / Termux MCP (Cloudflare tunnel, secret MCP path)
  -> browser_* MCP tools
  -> localhost-only bridge 127.0.0.1:8951
  -> Debian 12 (proot-distro)
  -> Linux Node.js /usr/bin/node
  -> Playwright
  -> Chromium headless

ChatGPT
  -> Kati / Termux MCP
  -> android_* MCP tools
  -> Termux rish
  -> Shizuku
  -> Android uid=2000(shell)
  -> WindowManager / UIAutomator / input / screencap / am/monkey
```

The browser bridge is **not** exposed through Cloudflare and binds only to `127.0.0.1`. It has a separate private local token in `~/.config/termux-mcp/browser-bridge-secret`. Do not print or share that token.

The existing MCP secret route is unchanged. Legacy `/mcp` remains disabled while `secretPathEnabled=true`.

## Installed runtime

- Debian: Debian GNU/Linux 12 (bookworm), aarch64
- Debian Node: `/usr/bin/node`
- Playwright project: `~/.local/share/termux-mcp-browser-bridge`
- Browser service: `~/.local/share/termux-mcp-browser-bridge/service.mjs`
- Persistent Chromium profile: `~/.local/share/termux-mcp-browser-profile`
- Browser screenshots: `~/.local/share/termux-mcp-browser/screenshots`
- Browser downloads: `~/.local/share/termux-mcp-browser/downloads`
- Browser log: `~/.local/state/termux-mcp-browser/bridge.log`
- Android screenshots: `~/.local/share/termux-mcp-android/screenshots`

The persistent browser profile is private Termux storage, not `/storage/emulated/0`.

## Browser lifecycle

`browser_start` starts the localhost bridge and Chromium context. Browser actions also start the bridge on demand if it is not already running. `browser_stop` closes Chromium and stops the local bridge.

The bridge is a child of the Termux MCP process, not a new public service and not a boot-persistence mechanism. On Termux MCP SIGTERM/SIGINT it is shut down. After a phone reboot, start the existing MCP normally with:

```sh
termux-mcp start
```

Then call `browser_start` or any browser action when browser automation is needed.

## Browser tools

- `browser_status`
- `browser_start`
- `browser_stop`
- `browser_navigate`
- `browser_snapshot`
- `browser_click`
- `browser_fill`
- `browser_type`
- `browser_select`
- `browser_check`
- `browser_uncheck`
- `browser_press`
- `browser_wait`
- `browser_get_text`
- `browser_get_attribute`
- `browser_screenshot`
- `browser_upload`
- `browser_tabs`
- `browser_new_tab`
- `browser_switch_tab`
- `browser_close_tab`
- `browser_back`
- `browser_forward`
- `browser_reload`
- `browser_downloads`

Locator priority is role + accessible name, then label, placeholder, text, test id, stable CSS, XPath last.

`browser_snapshot` deliberately does not return input values or cookies. Password values are blocked from `browser_get_attribute`. Browser request logging does not log form payloads, cookies, Authorization headers, or payment data.

`browser_upload` accepts existing files only inside the Termux MCP allowed roots (currently Termux home and `/storage/emulated/0`) and re-checks the real path to prevent traversal through symlinks.

Potential final/irreversible clicks such as purchase/payment/delete/publish/application/signing actions are blocked by the browser bridge unless the MCP caller supplies `confirmFinalAction="FINAL_ACTION"` after explicit user authorization. This is an additional guard, not a substitute for checking the final page state.

`browser_evaluate` was intentionally not exposed: arbitrary page JavaScript could bypass the final-action safety layer. The normal browser tools cover the requested form/navigation workflow.

## Browser diagnostics

Use `browser_status` first. It reports Debian, architecture, Debian Node path/version, Playwright version, Chromium executable, whether the bridge is running, whether Chromium has started, and private paths.

Browser bridge log:

```sh
tail -n 100 ~/.local/state/termux-mcp-browser/bridge.log
```

Termux MCP logs remain managed by the existing controller:

```sh
termux-mcp logs
```

## Android / Shizuku-rish tools

Installed MCP tools:

- `android_status`
- `android_screenshot`
- `android_dump_ui`
- `android_current_activity`
- `android_tap`
- `android_swipe`
- `android_input_text`
- `android_press_key`
- `android_launch_app`

Read-only Android operations do not require a mutation confirmation. UI-changing tools require the literal `ANDROID_UI` confirmation and must be grounded in an explicit user instruction.

Normal Android control uses `Kati -> Termux -> rish -> Shizuku -> Android shell`. When Shizuku is running and Termux/rish remains authorized, ADB pairing and Android Wireless Debugging are not required. ADB may remain installed only for optional diagnostics/fallback investigation; the nine `android_*` tools do not depend on an ADB-connected device.

Required local files are `~/shizuku_tmp/rish` and `~/shizuku_tmp/rish_shizuku.dex`. The transport sets `RISH_APPLICATION_ID=com.termux` and the matching `RISH_DISH_PATH` internally. `android_status` reports `transport=shizuku-rish`, rish/dex availability, shell reachability, identity, user, device model, and display size. The expected shell identity is `uid=2000(shell)` / `shell`.

Shizuku must be running and Termux/rish must remain authorized. Depending on Android/Shizuku startup mode, Shizuku may need to be started again after a phone reboot. If rish times out, check Shizuku state/authorization and battery restrictions for both Termux and Shizuku.

`android_input_text` uses Android's standard `input text`; complex Unicode/IME behavior can vary. `android_dump_ui` uses a unique UIAutomator XML file under `/data/local/tmp` and removes only that operation's file after reading it. Screenshots are streamed directly from `screencap -p` through rish and saved privately under `~/.local/share/termux-mcp-android/screenshots` with mode `0600`.

The optional legacy `serial` input remains in the MCP schemas for compatibility, but a supplied serial is rejected in local Shizuku mode rather than silently routing to another device.

## Cloudflare transport constraint

On the current network the managed Cloudflare Quick Tunnel must use QUIC. TCP port 7844 was verified to time out, while UDP/QUIC 7844 works. The controller therefore intentionally uses `--protocol quic`; do not revert it to forced `http2`.

When only MCP code changes, deploy with `termux-mcp restart-server`. Do not restart the Quick Tunnel unless independently necessary, because restarting it changes the `trycloudflare.com` hostname.

## Verification / build

Type-check and build the MCP source:

```sh
cd ~/termux-mcp
npm run typecheck
npm run build
```

A deployment should be tested on a temporary local MCP port before restarting the production server. The existing Cloudflare tunnel does not need to change when only the server is restarted (`termux-mcp restart-server`).

## Backup and rollback

Original pre-change backup location is recorded in:

```sh
cat ~/.local/state/termux-mcp-backups/LAST_BACKUP
```

Backup created for this deployment:

`~/.local/state/termux-mcp-backups/20260801-163244/`

It contains the original `src`, `package.json`, `package-lock.json`, `start.sh`, TypeScript configs, and existing Termux MCP control/watchdog scripts; `node_modules` was intentionally not copied.

Rollback principle: restore the backed-up project files, compile the restored source to `dist`, then use `termux-mcp restart-server` so the existing Cloudflare tunnel URL is preserved. Do not restart the tunnel unless independently necessary.
