#!/usr/bin/env bash
set -eu
set -o pipefail

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS_FILE="${MOVIA_MCP_TOOLS_FILE:-$HOME/termux-mcp/src/movia-tools.ts}"

if [ ! -f "$TOOLS_FILE" ]; then
    printf '%s\n' '{"script":"06_mcp_inventory","pass":false,"error":"Movia MCP tools source is missing"}'
    exit 1
fi

TOOLS_FILE="$TOOLS_FILE" python3 - <<'PY'
import json
import os
import re
import sys
from pathlib import Path

path = Path(os.environ["TOOLS_FILE"])
source = path.read_text(encoding="utf-8")

expected_tools = [
    "movia_snapshot",
    "movia_diagnostics",
    "movia_events",
    "movia_capabilities",
    "movia_manifest",
    "movia_actions",
    "movia_ui",
    "movia_streams",
    "movia_settings",
    "movia_operation",
    "movia_operation_poll",
    "movia_catalog_query",
    "movia_search",
    "movia_people_search",
    "movia_media_details",
    "movia_play",
    "movia_pause",
    "movia_seek",
    "movia_select_stream",
    "movia_select_quality",
    "movia_select_voice",
    "movia_my_list",
    "movia_my_list_add",
    "movia_my_list_remove",
    "movia_download_enqueue",
    "movia_download_status",
    "movia_download_delete",
    "movia_settings_set",
    "movia_action",
]
expected_actions = [
    "catalog.query",
    "catalog.search",
    "people.search",
    "media.details",
    "media.play",
    "player.pause",
    "player.seek",
    "player.selectStream",
    "player.selectQuality",
    "player.selectVoice",
    "library.snapshot",
    "library.setMyList",
    "downloads.enqueue",
    "downloads.status",
    "downloads.delete",
    "settings.set",
]
expected_bindings = {
    "catalogQuery": "catalog.query",
    "catalogSearch": "catalog.search",
    "peopleSearch": "people.search",
    "mediaDetails": "media.details",
    "play": "media.play",
    "pause": "player.pause",
    "seek": "player.seek",
    "streamSelect": "player.selectStream",
    "qualitySelect": "player.selectQuality",
    "voiceSelect": "player.selectVoice",
    "myList": "library.snapshot",
    "myListAdd": "library.setMyList",
    "myListRemove": "library.setMyList",
    "downloadEnqueue": "downloads.enqueue",
    "downloadStatus": "downloads.status",
    "downloadDelete": "downloads.delete",
    "settingsSet": "settings.set",
}
stale_action_fragments = [
    "playback.",
    "stream.select",
    "mylist.",
    "my_list.",
    "download.enqueue",
    "download.status",
    "download.delete",
]

registered = re.findall(r'server\.registerTool\(\s*"([^"]+)"', source)
actions_block = source.split("const ACTIONS = {", 1)[1].split("} as const;", 1)[0]
missing_tools = [name for name in expected_tools if name not in registered]
missing_actions = [action for action in expected_actions if f'"{action}"' not in source]
missing_bindings = [
    f"{key} -> {action}"
    for key, action in expected_bindings.items()
    if not re.search(rf'\b{re.escape(key)}:\s*\["{re.escape(action)}"\]', actions_block)
]
stale_actions = [fragment for fragment in stale_action_fragments if fragment in source]
token_literals = re.findall(r"(?i)\b(?:bearer\s+)?[0-9a-f]{64}\b", source)
token_plumbing = [word for word in ("TOKEN_FILE", "ensureToken", "Authorization", "Bearer") if word in source]

checks = {
    "registeredToolCount": len(registered),
    "expectedToolsPresent": not missing_tools,
    "missingTools": missing_tools,
    "realActionIdsPresent": not missing_actions,
    "missingActionIds": missing_actions,
    "canonicalActionBindings": not missing_bindings,
    "missingActionBindings": missing_bindings,
    "noLegacyActionIds": not stale_actions,
    "legacyActionFragments": stale_actions,
    "noTokenLiteral": not token_literals,
    "noTokenPlumbingInToolRegistration": not token_plumbing,
}
passed = all(
    checks[key]
    for key in (
        "expectedToolsPresent",
        "realActionIdsPresent",
        "canonicalActionBindings",
        "noLegacyActionIds",
        "noTokenLiteral",
        "noTokenPlumbingInToolRegistration",
    )
)
print("PASS MCP first-class tool inventory" if passed else "FAIL MCP first-class tool inventory")
print(json.dumps({"script": "06_mcp_inventory", "pass": passed, "checks": checks}, separators=(",", ":")))
sys.exit(0 if passed else 1)
PY
