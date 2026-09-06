# Movia agent-native acceptance

These checks exercise the installed Movia loopback control plane and the native Jarvis MCP adapter. Normal agent operations use authenticated HTTP on `127.0.0.1:8899` and do not require a visible Activity or Shizuku.


## One-command smoke acceptance

The release-readiness smoke gate is available directly as:

```sh
python3 acceptance/movia_acceptance.py
```

and through the Jarvis MCP tool `movia_acceptance`. The smoke run no longer uses fixed control titles: each run selects an unfiltered random movie plus capability-matched multilingual, multi-quality, and series samples from the current catalog and records the random seed for reproduction. It runs live backend, Android/Media3, 10-second startup-budget, physical audio-language, quality-control, and series navigation/resume checks. It always emits structured JSON with `total`, `passed`, `failed`, `success_rate`, `stabilization_gate`, `release_gate`, `sample_seed`, `samples`, `errors`, and per-check evidence. `stabilization_gate` uses the interim 98% threshold; `release_gate` and the process exit code require 100% of smoke checks to pass.

This smoke suite is the control framework, not the final 1.0 catalog-coverage gate. Version 1.0.0 still requires the separate 100-random-movie plus series coverage run at 100% acceptance.
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

### Random catalog coverage

`08_playback_coverage_random.py` uses a fresh seed by default, enforces a per-item startup budget of at most 10 seconds, and treats every non-PLAYABLE catalog sample (including `NO_SOURCE`) as a failure. Reproduce a run with `--seed`. Example:

```sh
python3 acceptance/08_playback_coverage_random.py --movies 20 --series 20 --timeout 10
```

The final 1.0 acceptance will use the same strict rule with the required 100-random-movie sample plus a separate series sample.
