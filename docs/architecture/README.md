# Movia architecture

## System boundaries

Android in android/ owns the user experience, local Room catalog state, Media3
player and native agent control plane.

Backend in backend/ is the media-parser service. It owns catalog discovery,
normalization/search, stream resolution, torrent/P2P helpers and the streamer
HTTP service.

Agent in agent/ contains the Android agent contract and the Termux MCP bridge.
The Android native action registry and MCP tool names are source contracts; the
MCP generated dist/ directory is rebuilt, not committed.

Catalog data is deliberately separated from source. The live SQLite catalog and
its WAL/SHM sidecars remain in the private Termux backend directory. Git stores
schema, migrations, recovery code and a baseline manifest.

## Runtime flow

1. Android requests catalog/search or playback data from the backend.
2. Backend resolves canonical media identity and available stream variants.
3. Android Media3 receives a resolved media source and owns playback state.
4. Agent requests are validated against the native action registry.
5. Termux MCP exposes the source-registered Movia tools and forwards safe
   operations to the local agent bridge.

## Canonical paths

The user-facing root is /storage/emulated/0/Movia. The live source roots used for
this synchronization are recorded in reference/CURRENT_PHONE_STATE.json and
CURRENT_BASELINE.json.
