"""One-time offline migration: explicit authorized reference file -> data profile."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "runtime"))
from zona_client_profile import MAX_ARTIFACT_BYTES, MODULUS, validate_profile


def build(source: Path) -> dict:
    size = source.stat().st_size
    if not 0 < size <= MAX_ARTIFACT_BYTES:
        raise ValueError("Invalid source size")
    counts, weights = [0] * 256, [0] * 256
    digest = hashlib.sha256()
    remaining = size
    with source.open("rb") as handle:
        for chunk in iter(lambda: handle.read(256 * 1024), b""):
            digest.update(chunk)
            for byte in chunk:
                counts[byte] += 1
                weights[byte] = (weights[byte] + remaining) % MODULUS
                remaining -= 1
    if remaining != 0:
        raise ValueError("Source changed while building profile")
    return validate_profile({
        "format": "movia-zona-byte-profile-v1",
        "artifact_sha256": digest.hexdigest(), "artifact_bytes": size,
        "counts": counts, "position_weights": weights,
    })


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    value = build(args.source)
    args.output.write_text(json.dumps(value, separators=(",", ":")) + "\n")
    print(json.dumps({"status": "CREATED", "bytes": args.output.stat().st_size,
                      "artifactSha256": value["artifact_sha256"]}))
