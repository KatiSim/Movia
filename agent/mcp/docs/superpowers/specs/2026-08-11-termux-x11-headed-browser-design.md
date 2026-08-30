# Termux:X11 Headed Browser Design

## Goal

Run an isolated Linux desktop and headed Chromium on the second Android phone through Termux + Termux:X11 + Debian proot, controlled by the existing localhost-only Playwright browser bridge, without using Shizuku/Android UI automation for website interaction.

## Architecture

- Termux owns the Termux:X11 server on display `:1` and optional PulseAudio.
- Existing Debian 12 is entered with `proot-distro login debian --shared-tmp` so the X11 socket is visible inside the proot environment.
- XFCE is the single desktop environment.
- Chromium runs inside Debian and is visible in Termux:X11.
- The existing Playwright bridge remains bound to `127.0.0.1:8951` and keeps its token authentication.
- The bridge launches a persistent Chromium context with `headless: false`, `DISPLAY=:1`, and the existing persistent profile directory.
- Browser MCP tools continue to provide navigate/snapshot/click/fill/screenshot/tab/download operations.

## Files / commands

User-facing launch scripts in `$HOME/bin`:

- `start-x11.sh`: start PulseAudio if needed and Termux:X11 `:1`.
- `start-desktop.sh`: enter Debian with `--shared-tmp`, export `DISPLAY=:1`, start XFCE through `dbus-launch`.
- `start-browser-bridge.sh`: ensure X11 is reachable, then start the existing localhost bridge.
- `stop-browser-bridge.sh`: stop the persistent browser context/bridge without touching Android UI.
- `browser-status.sh`: report X11, XFCE, bridge, Chromium, profile and listening socket state without secrets.

## Persistent browser profile

Keep the existing profile path:

`$HOME/.local/share/termux-mcp-browser-profile`

Cookies and login sessions must survive bridge/browser restarts. No cookies, passwords, tokens, NIE, DOB, SMS/OTP, passport or banking values are printed or logged.

## Sensitive-field safety

The bridge must refuse automated `fill`/`type` for fields whose visible or programmatic identity indicates passwords, one-time codes, NIE/NIF/DNI/passport, date of birth, card/bank/account/IBAN data, or similar authentication/identity secrets unless a future explicit policy mechanism is deliberately added. Current task policy is fail closed: stop and ask the user to type those values manually in the headed browser.

Snapshots must not return current input values or cookies.

## Testing

1. Verify packages and Debian desktop commands.
2. Verify Termux:X11 process and X socket.
3. Verify XFCE can start with `--shared-tmp` and `DISPLAY=:1`.
4. Launch headed Playwright Chromium; open `https://example.com`, verify title, screenshot and visible browser process.
5. Verify persistence with a non-sensitive local test cookie across browser restart.
6. Verify sensitive-field guard with a local HTML fixture.
7. Navigate to `https://cmtbc.es`, locate the Tarjeta Joven status section, and stop before entering NIE/date-of-birth or other sensitive data.

## Constraints

- No Shizuku clicks, Android Accessibility automation, or control of the main Android screen for web workflows.
- Bridge remains localhost-only.
- No public bind/noVNC exposure without a separate explicit user request.
- Do not reinstall Debian if healthy.
- Do not install a second desktop environment when XFCE is available.
