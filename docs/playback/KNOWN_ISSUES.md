# Playback known issues at current baseline

This is a current checkpoint, not a stability claim.

Open evidence:

- media identity can be lost or mismatched across episode selection;
- stream candidates can exist while playback still times out;
- Zona aggregation/P2P exact-episode selection needs a reproducible
  end-to-end acceptance case;
- a full playback run was not executed during this synchronization.

Relevant source areas include Android playback session/player code, backend
streamer/torrent resolver code and the playback decision records under
docs/decisions/.

Any fix must add a focused regression test, update the evidence, document the
acceptance result and then be committed/pushed as one logical change.
