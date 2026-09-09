# Movia private configuration / secrets

No real Internet credential, GitHub credential, private signing key, provider cookie or private API token is stored in this repository.

## Backend

Create a private file at `$HOME/projects/media-parser/.env` when needed. Start from `.env.example` and fill values privately.

Common private value:

- `TMDB_API_KEY` or the TMDB authentication variable consumed by `backend/config.py`/`tmdb_client.py`.

Provider-specific credentials, if a configured provider ever requires them, belong in the same private environment and must not be committed.

## Termux MCP

Private/environment values:

- `TERMUX_MCP_SECRET`
- optional host/port/root/job-root overrides.

The native Movia agent token is provisioned with `agent/tools/provision-agent-token.sh` into its private location.

## Android signing

The development baseline expects `$HOME/.android/debug.keystore`. The actual key file is not stored on GitHub. `scripts/bootstrap-debug-keystore.sh` can generate a new development key after a full uninstall. A different key cannot update an already-installed package signed by the old key.

## Local aria2 RPC

`agent/runtime/aria2.conf.example` contains a fixed loopback-only RPC token used by the local 0.9.32 backend contract. It is not an Internet credential and the RPC listener is explicitly non-public (`rpc-listen-all=false`).

## GitHub

Git authentication remains in the user's credential helper/session outside this repository. Never copy credential stores or tokens into recovery files.

## Before every push

Run a secret-pattern scan and `git diff --check`. If any real credential is ever exposed, revoke/rotate it at its provider before continuing.
