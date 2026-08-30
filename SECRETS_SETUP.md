# Secrets setup

GitHub, backend, agent and external-provider credentials are local-only.

## Required secret classes

- Movia Agent authorization token.
- Backend provider/API keys, if the current backend requires them.
- Cookies or authenticated provider sessions, if explicitly required.
- Private Android signing material and passwords.

## Local locations

Use ignored local configuration or the existing secret storage:

- `~/.config/movia-agent/token`
- local `.env` copied from `.env.example`
- local `config.local`
- private keystore outside the repository

The exact consumer must be documented in the service definition before use. Never place the value in source, CURRENT_BASELINE.json, README, issue text, logs, APK metadata or GitHub Release notes.

## Scan before commit

~~~bash
git diff --check
git grep -n -I -E '(ghp_|github_pat_|Bearer[[:space:]]+[A-Za-z0-9._-]+|api[_-]?key|password|cookie|token[[:space:]]*=)' -- ':!SECRETS_SETUP.md' ':!.env.example' ':!config.example' || true
~~~

If a credential was exposed, revoke/rotate it at the provider. A token intentionally retained in `~/.config/gh/hosts.yml` is not part of the Movia project and must never be copied into it.
