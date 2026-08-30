# Secrets setup

No real token, cookie, API key, password, signing key or private credential is
stored in this repository or its GitHub Releases.

## Backend

The backend reads configuration through backend/config.py and python-dotenv.
Create a private backend .env in the Termux backend working directory:

    /data/data/com.termux/files/home/projects/media-parser/.env

Typical private values include the TMDB API key and any source-specific
credentials required by the configured discovery integrations. Use the names
expected by backend/config.py. Do not copy the file into backend/ or Git.

## Termux MCP

The MCP server reads TERMUX_MCP_HOST, TERMUX_MCP_PORT, TERMUX_MCP_SECRET,
TERMUX_MCP_ROOTS and TERMUX_MCP_JOB_ROOT from the environment as defined in
agent/mcp/src/config.ts. Keep the secret in the Termux environment or a private
shell/service configuration.

## Native Movia Agent

The native agent provisioning helper is
agent/tools/provision-agent-token.sh. It writes the private agent token to the
private configuration location expected by the current Android/Termux
integration. Run it interactively or with the private environment documented by
the script. Never paste the value into GitHub Issues, source, manifests or logs.

## GitHub credentials

Git credentials are held by the Termux credential helper outside this project.
They are not copied into the canonical root. Never print them or put them in
.env.example, config.example, CURRENT_BASELINE.json or release assets.

## Rotation

If a credential is exposed, revoke/rotate it at the provider and provision the
replacement privately. A repository secret scan is required before every
commit and push.
