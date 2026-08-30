# Movia agent-native acceptance

These checks exercise the installed Movia loopback control plane and the native Jarvis MCP adapter. Normal agent operations use authenticated HTTP on `127.0.0.1:8899` and do not require a visible Activity or Shizuku.

After a **fresh install or app-data clear only**, provision the shared bearer token once:

```sh
bash tools/provision-agent-token.sh
```

The helper transfers the token through stdin to `run-as app.movia.android`, writes it only to app-private `files/agent/movia-agent.token`, verifies it by SHA-256, and does not print the token. Ordinary upgrades preserve the file and do not require provisioning again.

Run acceptance in this order:

```sh
bash acceptance/01_headless_cold.sh
python3 acceptance/02_smoke.py
python3 acceptance/03_benchmark.py
python3 acceptance/04_operation.py
python3 acceptance/05_breaking_bad.py
bash acceptance/06_mcp_inventory.sh
python3 acceptance/07_final_acceptance.py
```

`01_headless_cold.sh` uses `rish` only to execute the Movia-scoped test reset `am force-stop app.movia.android` and to read the foreground Activity. It does not change Android settings, clear data, uninstall packages, inject input, or open Movia UI. The production CLI wakes Movia with an explicit **wake-only** broadcast; the broadcast carries no credentials. API authorization remains the private 64-hex bearer token.

`02`–`05` exercise the authenticated loopback API. `05_breaking_bad.py` validates the requested/active `720p + Кубик в Кубе` pair. Visible screenshot/fullscreen/PiP pixel checks remain deliberately separate from headless domain acceptance.

`06_mcp_inventory.sh` checks the live Jarvis source at `~/termux-mcp/src/movia-tools.ts` by default (override with `MOVIA_MCP_TOOLS_FILE`). TypeScript gates remain:

```sh
(cd ~/termux-mcp && npm run typecheck && npm run build)
```

`07_final_acceptance.py` is the additive final-phase gate. It checks the
PlayerScreen spinner and DetailsScreen sheet contracts from source, rechecks
loopback/401/wake-only/no-Shizuku invariants, measures one headless
`media.play` probe, and reports operation/resolve timing plus stream, voice,
and quality coverage. A completed pipeline with fewer than two upstream voices
is reported as `provider_coverage_limitation` and does not fail the global
gate; an operation or diagnostics failure is reported as `pipeline_failure`.

The catalog check performs only `GET /api/catalog/sync-status` when that route
is available. It requires an empty `last_error` and a last-finished/successful
timestamp no more than 900 seconds old (three intended 300-second cadences).
It never calls a sync trigger. The final line is a compact JSON summary for
orchestration. Use `python3 acceptance/07_final_acceptance.py --source-only`
for build-host source checks without a running agent.
