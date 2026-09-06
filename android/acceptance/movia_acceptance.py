#!/data/data/com.termux/files/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import random
import secrets
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

CONFIG_DIR = Path(os.environ.get("MOVIA_CONFIG_DIR", str(Path.home() / ".config/movia-agent")))
TOKEN_FILE = Path(os.environ.get("MOVIA_TOKEN_FILE", str(CONFIG_DIR / "token")))
AGENT_BASE_URL = os.environ.get("MOVIA_AGENT_URL", "http://127.0.0.1:8899/agent/v1").rstrip("/")
BACKEND_BASE_URL = os.environ.get("MOVIA_BACKEND_URL", "http://127.0.0.1:8888").rstrip("/")
HTTP_TIMEOUT = float(os.environ.get("MOVIA_HTTP_TIMEOUT", "6.0"))
OP_TIMEOUT = float(os.environ.get("MOVIA_OPERATION_TIMEOUT", "10.0"))
STARTUP_LIMIT_SECONDS = float(os.environ.get("MOVIA_STARTUP_LIMIT_SECONDS", "10.0"))

BOOTSTRAP_COMPONENT = "app.movia.android/.agent.AgentBootstrapReceiver"
BOOTSTRAP_ACTION = "app.movia.android.agent.BOOTSTRAP"
CATALOG_DB = Path(os.environ.get(
    "MOVIA_CATALOG_DB",
    str(Path.home() / "projects/media-parser/catalog.db"),
))
RANDOM_PROBE_TIMEOUT_SECONDS = min(STARTUP_LIMIT_SECONDS, 10.0)


def resolve_sample_seed() -> int:
    raw = os.environ.get("MOVIA_ACCEPTANCE_SEED", "").strip()
    if raw:
        return int(raw, 0)
    return secrets.randbits(32)


def _target(row: sqlite3.Row) -> Dict[str, Any]:
    return {"mediaId": str(row["id"]), "title": str(row["title"])}


def select_catalog_targets(seed: int) -> Dict[str, Dict[str, Any]]:
    """Choose fresh catalog samples by capability, never by hard-coded title/id."""
    if not CATALOG_DB.is_file():
        raise RuntimeError(f"catalog database not found: {CATALOG_DB}")
    rng = random.Random(seed)
    with sqlite3.connect(str(CATALOG_DB)) as conn:
        conn.row_factory = sqlite3.Row
        movies = conn.execute(
            "SELECT id, title FROM movies WHERE media_type='movie' AND title != '' ORDER BY id"
        ).fetchall()
        audio_movies = conn.execute(
            """
            SELECT m.id, m.title
            FROM movies m
            WHERE m.media_type='movie' AND m.title != ''
              AND json_valid(COALESCE(m.streams, '[]')) = 1
              AND EXISTS (
                SELECT 1 FROM json_each(COALESCE(m.streams, '[]')) j
                WHERE json_extract(j.value, '$.url') LIKE 'http%'
                  AND (
                    lower(COALESCE(json_extract(j.value, '$.voice'), '')) LIKE '%укр%'
                    OR lower(COALESCE(json_extract(j.value, '$.language'), '')) = 'uk'
                  )
              )
              AND EXISTS (
                SELECT 1 FROM json_each(COALESCE(m.streams, '[]')) j
                WHERE json_extract(j.value, '$.url') LIKE 'http%'
                  AND (
                    lower(COALESCE(json_extract(j.value, '$.stream_id'), '')) LIKE '%english%'
                    OR lower(COALESCE(json_extract(j.value, '$.voice'), '')) LIKE '%english%'
                    OR lower(COALESCE(json_extract(j.value, '$.language'), '')) = 'en'
                  )
              )
            ORDER BY m.id
            """
        ).fetchall()
        quality_movies = conn.execute(
            """
            SELECT m.id, m.title
            FROM movies m
            WHERE m.media_type='movie' AND m.title != ''
              AND json_valid(COALESCE(m.streams, '[]')) = 1
              AND EXISTS (
                SELECT 1 FROM json_each(COALESCE(m.streams, '[]')) j
                WHERE json_extract(j.value, '$.url') LIKE 'http%'
              )
              AND (
                SELECT COUNT(DISTINCT lower(COALESCE(json_extract(j.value, '$.quality'), '')))
                FROM json_each(COALESCE(m.streams, '[]')) j
                WHERE trim(COALESCE(json_extract(j.value, '$.quality'), '')) NOT IN ('', 'Не указано')
              ) >= 2
            ORDER BY m.id
            """
        ).fetchall()
        series_rows = conn.execute(
            """
            SELECT m.id, m.title, m.season_episode_counts, m.streams
            FROM movies m
            WHERE m.media_type='tv' AND m.title != '' AND m.seasons_count > 0
              AND json_valid(COALESCE(m.streams, '[]')) = 1
              AND EXISTS (
                SELECT 1 FROM json_each(COALESCE(m.streams, '[]')) j
                WHERE json_extract(j.value, '$.url') LIKE 'http%'
              )
            ORDER BY m.id
            """
        ).fetchall()

    if not movies:
        raise RuntimeError("catalog contains no movies")

    random_movie_row = rng.choice(movies)
    audio_row = rng.choice(audio_movies) if audio_movies else None
    quality_pool = [r for r in quality_movies if str(r["id"]) != str(audio_row["id"]) ] if audio_row else list(quality_movies)
    quality_row = rng.choice(quality_pool or quality_movies) if quality_movies else None
    series_row = rng.choice(series_rows) if series_rows else None

    targets: Dict[str, Dict[str, Any]] = {
        "randomMovie": _target(random_movie_row),
    }
    if audio_row is not None:
        targets["audioMovie"] = _target(audio_row)
    if quality_row is not None:
        targets["qualityMovie"] = _target(quality_row)
    if series_row is not None:
        series_target = _target(series_row)
        counts: List[int] = []
        try:
            parsed_counts = json.loads(series_row["season_episode_counts"] or "[]")
            counts = [int(v) for v in parsed_counts if int(v) > 0]
        except Exception:
            counts = []
        direct_episodes: List[Tuple[int, int]] = []
        try:
            parsed_streams = json.loads(series_row["streams"] or "[]")
            for stream in parsed_streams if isinstance(parsed_streams, list) else []:
                if not isinstance(stream, dict):
                    continue
                if not str(stream.get("url") or "").startswith(("http://", "https://")):
                    continue
                season = int(stream.get("season") or 0)
                episode = int(stream.get("episode") or 0)
                if season > 0 and episode > 0:
                    direct_episodes.append((season, episode))
        except Exception:
            direct_episodes = []
        viable = []
        for season, episode in sorted(set(direct_episodes)):
            if 1 <= season <= len(counts) and episode < counts[season - 1]:
                viable.append((season, episode))
        season, episode = rng.choice(viable or direct_episodes or [(1, 1)])
        series_target.update({"season": season, "episode": episode})
        targets["series"] = series_target
    return targets


def load_token() -> str:
    if TOKEN_FILE.is_file():
        token = TOKEN_FILE.read_text(encoding="utf-8").strip()
        if len(token) == 64 and all(c in "0123456789abcdefABCDEF" for c in token):
            return token
    completed = subprocess.run(
        ["run-as", "app.movia.android", "cat", "files/agent/movia-agent.token"],
        capture_output=True, text=True, timeout=3, check=False,
    )
    token = completed.stdout.strip()
    if len(token) != 64 or not all(c in "0123456789abcdefABCDEF" for c in token):
        raise RuntimeError("Movia agent token unavailable")
    TOKEN_FILE.parent.mkdir(parents=True, exist_ok=True)
    TOKEN_FILE.write_text(token, encoding="utf-8")
    os.chmod(TOKEN_FILE, 0o600)
    return token


def wake_android_app() -> None:
    subprocess.run(
        ["am", "broadcast", "--user", "current", "-f", "0x20", "-n", BOOTSTRAP_COMPONENT, "-a", BOOTSTRAP_ACTION],
        capture_output=True, timeout=5, check=False,
    )


def http_json(url: str, *, method: str = "GET", payload: Optional[Dict[str, Any]] = None,
              headers: Optional[Dict[str, str]] = None, timeout: float = HTTP_TIMEOUT) -> Tuple[int, Optional[Any], str]:
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req_headers = {"Accept": "application/json", **(headers or {})}
    if body is not None:
        req_headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=body, headers=req_headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
            return resp.status, json.loads(raw), ""
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        return exc.code, None, raw[:240]
    except Exception as exc:
        return 0, None, str(exc)[:240]


def agent_http(path: str, method: str = "GET", payload: Optional[Dict[str, Any]] = None,
               timeout: float = HTTP_TIMEOUT) -> Tuple[int, Optional[Any], str]:
    try:
        token = load_token()
    except Exception as exc:
        return 0, None, f"token error: {exc}"
    return http_json(
        AGENT_BASE_URL + path,
        method=method,
        payload=payload,
        headers={"Authorization": f"Bearer {token}"},
        timeout=timeout,
    )


def backend_http(path: str, timeout: float = HTTP_TIMEOUT) -> Tuple[int, Optional[Any], str]:
    return http_json(BACKEND_BASE_URL + path, timeout=timeout)


def action(name: str, args: Optional[Dict[str, Any]] = None) -> Tuple[int, Optional[Any], str]:
    return agent_http("/action", method="POST", payload={
        "action": name,
        "arguments": args or {},
        "requestId": f"accept-{uuid.uuid4().hex[:12]}",
    })


def poll(operation_id: str, timeout: float = OP_TIMEOUT) -> Tuple[str, Optional[Dict[str, Any]]]:
    deadline = time.monotonic() + timeout
    last = None
    encoded = urllib.parse.quote(operation_id, safe="")
    while time.monotonic() < deadline:
        status, payload, _ = agent_http(f"/operations?operationId={encoded}", timeout=3.0)
        if status == 200 and isinstance(payload, dict) and isinstance(payload.get("operation"), dict):
            last = payload["operation"]
            state = str(last.get("status") or last.get("state") or "").upper()
            if state in {"COMPLETED", "FAILED", "CANCELLED", "REJECTED"}:
                return state, last
        time.sleep(0.25)
    return "TIMEOUT", last


def accepted_operation(name: str, args: Optional[Dict[str, Any]] = None, timeout: float = OP_TIMEOUT) -> Tuple[bool, str, Optional[Dict[str, Any]]]:
    status, payload, err = action(name, args)
    if status != 200 or not isinstance(payload, dict) or payload.get("status") != "accepted":
        return False, err or f"action {name} status={status}", None
    op_id = payload.get("operationId")
    if not op_id:
        return False, "operationId missing", None
    terminal, op = poll(str(op_id), timeout)
    if terminal != "COMPLETED":
        code = op.get("errorCode") if isinstance(op, dict) else None
        return False, f"operation {terminal}, errorCode={code}", op
    return True, "", op


def diagnostics() -> Optional[Dict[str, Any]]:
    status, payload, _ = agent_http("/diagnostics", timeout=4.0)
    return payload if status == 200 and isinstance(payload, dict) else None


def streams_payload() -> Optional[Dict[str, Any]]:
    status, payload, _ = agent_http("/streams", timeout=4.0)
    return payload if status == 200 and isinstance(payload, dict) else None


def reset_player() -> None:
    # Isolate probes. A timed-out asynchronous media.play may keep resolving in
    # the background; without an explicit stop it can poison every later check.
    action("player.stop")
    time.sleep(0.2)


def wait_for_position_advance(seconds: float = 8.0) -> bool:
    deadline = time.monotonic() + seconds
    first: Optional[int] = None
    while time.monotonic() < deadline:
        d = diagnostics()
        m3 = d.get("media3") if isinstance(d, dict) else None
        if isinstance(m3, dict):
            pos = int(m3.get("currentPositionMs") or 0)
            if first is None:
                first = pos
            if bool(m3.get("isPlaying")) and pos > max(0, first) + 150:
                return True
        time.sleep(0.4)
    return False


class Runner:
    def __init__(self, verbose: bool = False):
        self.verbose = verbose
        self.checks: List[Dict[str, Any]] = []
        self.sample_seed = resolve_sample_seed()
        self.sample_error = ""
        try:
            self.samples = select_catalog_targets(self.sample_seed)
        except Exception as exc:
            self.samples: Dict[str, Dict[str, Any]] = {}
            self.sample_error = str(exc)

    def record(self, category: str, name: str, passed: bool, detail: str = "") -> None:
        item = {"category": category, "name": name, "passed": bool(passed)}
        if detail:
            item["detail"] = detail
        self.checks.append(item)
        if self.verbose:
            print(f"[{'PASS' if passed else 'FAIL'}] {category}: {name}" + (f" - {detail}" if detail else ""), file=sys.stderr)

    def run(self) -> Dict[str, Any]:
        self.sample_selection()
        self.backend()
        self.android()
        self.series()
        reset_player()
        total = len(self.checks)
        passed = sum(1 for c in self.checks if c["passed"])
        failed = total - passed
        rate = round((passed / total) * 100.0, 1) if total else 0.0
        return {
            "total": total,
            "passed": passed,
            "failed": failed,
            "success_rate": rate,
            "stabilization_gate": "PASS" if rate >= 98.0 else "FAIL",
            "release_gate": "PASS" if failed == 0 else "FAIL",
            "release_required_rate": 100.0,
            "startup_limit_seconds": STARTUP_LIMIT_SECONDS,
            "sample_seed": self.sample_seed,
            "samples": self.samples,
            "errors": [f"{c['category']}: {c['name']}: {c.get('detail','failed')}" for c in self.checks if not c["passed"]],
            "checks": self.checks,
        }


    def sample_selection(self) -> None:
        for key, label in (
            ("randomMovie", "random movie sample selected"),
            ("audioMovie", "multilingual movie sample selected"),
            ("qualityMovie", "multi-quality movie sample selected"),
            ("series", "series sample selected"),
        ):
            self.record(
                "SAMPLE",
                label,
                key in self.samples,
                self.sample_error if key not in self.samples else f"mediaId={self.samples[key].get('mediaId')}",
            )

    def backend(self) -> None:
        status, payload, err = backend_http("/health")
        self.record("BACKEND", "health", status == 200 and isinstance(payload, dict) and payload.get("status") == "ok", err if status != 200 else "")

        resolver_sample = self.samples.get("audioMovie")
        if resolver_sample is None:
            self.record("BACKEND", "sampled resolver", False, self.sample_error or "direct resolver sample unavailable")
        else:
            status, payload, err = backend_http(
                f"/api/movie/{urllib.parse.quote(str(resolver_sample['mediaId']), safe='')}/stream",
                timeout=5.0,
            )
            self.record(
                "BACKEND",
                "sampled resolver",
                status == 200 and isinstance(payload, dict) and bool(payload.get("streams")),
                err if status != 200 else ("no streams returned" if not (isinstance(payload, dict) and payload.get("streams")) else ""),
            )

        status, payload, err = backend_http("/diagnostics")
        self.record("BACKEND", "provider availability", status == 200 and isinstance(payload, dict) and payload.get("status") == "ok", err if status != 200 else "")

        status, payload, err = backend_http("/api/home")
        self.record("BACKEND", "metadata availability", status == 200 and isinstance(payload, dict) and bool(payload.get("popular") or payload.get("featured") or payload.get("hero")), err if status != 200 else "")

    def android(self) -> None:
        status, payload, err = agent_http("/health")
        if status != 200:
            wake_android_app(); time.sleep(1.0); status, payload, err = agent_http("/health")
        self.record("ANDROID", "agent/app process", status == 200 and isinstance(payload, dict) and payload.get("processAlive") is True, err if status != 200 else "")

        status, payload, err = action("catalog.query", {"limit": 1})
        self.record("ANDROID", "backend connection", status == 200 and isinstance(payload, dict) and payload.get("status") == "completed", err if status != 200 else "")

        # Unfiltered random catalog probe. This is intentionally not limited to
        # known-good titles: a user can choose any card in the catalog.
        reset_player()
        random_movie = self.samples.get("randomMovie")
        if random_movie is None:
            self.record("ANDROID", "random movie playback", False, self.sample_error or "sample unavailable")
        else:
            status, payload, err = action("media.details", {"mediaId": random_movie["mediaId"]})
            media = payload.get("media") if isinstance(payload, dict) else None
            self.record(
                "ANDROID",
                "open random catalog movie",
                status == 200 and isinstance(media, dict) and str(media.get("mediaId")) == str(random_movie["mediaId"]),
                err if status != 200 else "",
            )
            started_at = time.monotonic()
            ok, detail, _ = accepted_operation(
                "media.play",
                {"mediaId": random_movie["mediaId"], "title": random_movie["title"], "resume": False, "persist": False},
                timeout=RANDOM_PROBE_TIMEOUT_SECONDS,
            )
            elapsed = time.monotonic() - started_at
            self.record("ANDROID", "random movie playback", ok, detail or f"mediaId={random_movie['mediaId']}")
            self.record(
                "ANDROID",
                "random movie startup <= 10s",
                ok and elapsed <= STARTUP_LIMIT_SECONDS,
                f"mediaId={random_movie['mediaId']}, elapsedSeconds={elapsed:.2f}, limit={STARTUP_LIMIT_SECONDS:.2f}",
            )
            d = diagnostics()
            m3 = d.get("media3") if isinstance(d, dict) else None
            ready = ok and isinstance(m3, dict) and (str(m3.get("playbackState") or "").upper() == "READY" or m3.get("playbackStateCode") == 3)
            self.record("ANDROID", "random movie Media3 READY", ready, "" if ready else "Media3 did not report READY")
            timeline_ok = wait_for_position_advance(seconds=3.0) if ready else False
            self.record("ANDROID", "random movie advancing timeline", timeline_ok, "" if timeline_ok else "timeline did not advance")
        reset_player()

        # Capability-selected multilingual sample. The title changes with the seed.
        reset_player()
        audio_movie = self.samples.get("audioMovie")
        audio_media: Optional[Dict[str, Any]] = None
        audio_started = False
        if audio_movie is None:
            self.record("ANDROID", "multilingual probe playback", False, self.sample_error or "sample unavailable")
        else:
            status, payload, err = action("media.details", {"mediaId": audio_movie["mediaId"]})
            audio_media = payload.get("media") if isinstance(payload, dict) else None
            audio_started, audio_detail, _ = accepted_operation(
                "media.play",
                {"mediaId": audio_movie["mediaId"], "title": audio_movie["title"], "resume": False, "persist": False},
                timeout=min(7.0, STARTUP_LIMIT_SECONDS),
            )
            self.record("ANDROID", "multilingual probe playback", audio_started, audio_detail or f"mediaId={audio_movie['mediaId']}")

        media_streams = audio_media.get("streams") if isinstance(audio_media, dict) else []
        language_targets: Dict[str, str] = {}
        if isinstance(media_streams, list):
            for item in media_streams:
                if not isinstance(item, dict):
                    continue
                lang = str(item.get("language") or "").lower()
                voice = str(item.get("voice") or "")
                if lang in {"uk", "en"} and voice and lang not in language_targets:
                    language_targets[lang] = voice

        for lang, check_name in (("uk", "physical Ukrainian audio"), ("en", "physical Original/English audio")):
            target = language_targets.get(lang)
            if not audio_started or not target:
                self.record("ANDROID", check_name, False, f"no playable {lang} release voice exposed")
                continue
            v_ok, v_detail, _ = accepted_operation(
                "player.selectVoice",
                {"voice": target, "persist": False},
                timeout=4.0,
            )
            vd = diagnostics()
            vm3 = vd.get("media3") if isinstance(vd, dict) else None
            actual_language = str(vm3.get("selectedAudioLanguage") or "").lower() if isinstance(vm3, dict) else ""
            actual_label = str(vm3.get("selectedAudioLabel") or "") if isinstance(vm3, dict) else ""
            self.record(
                "ANDROID", check_name, v_ok and actual_language.startswith(lang),
                v_detail or f"requestedVoice={target}, selectedAudioLabel={actual_label}, selectedAudioLanguage={actual_language}",
            )

        reset_player()
        quality_movie = self.samples.get("qualityMovie")
        if quality_movie is None:
            self.record("ANDROID", "switch quality", False, self.sample_error or "multi-quality sample unavailable")
            return
        q_start, q_start_detail, _ = accepted_operation(
            "media.play",
            {"mediaId": quality_movie["mediaId"], "title": quality_movie["title"], "resume": False, "persist": False},
            timeout=min(7.0, STARTUP_LIMIT_SECONDS),
        )
        sp = streams_payload() if q_start else None
        qualities: List[str] = []
        if isinstance(sp, dict):
            for group in sp.get("qualities") or []:
                if isinstance(group, dict) and group.get("quality"):
                    qualities.append(str(group["quality"]))
        active_quality = str(sp.get("activeQuality") or "") if isinstance(sp, dict) else ""
        alternatives = [q for q in qualities if q.lower() != active_quality.lower()]
        if not q_start:
            self.record("ANDROID", "switch quality", False, f"quality probe playback failed: {q_start_detail}")
        elif not alternatives:
            self.record("ANDROID", "switch quality", False, f"only one playable quality exposed: {active_quality or qualities}")
        else:
            target = alternatives[0]
            q_ok, q_detail, _ = accepted_operation("player.selectQuality", {"quality": target, "persist": False}, timeout=3.0)
            after = streams_payload()
            actual = str(after.get("activeQuality") or "") if isinstance(after, dict) else ""
            self.record("ANDROID", "switch quality", q_ok and actual.lower() == target.lower(), q_detail or f"requested={target}, active={actual}")

    def series(self) -> None:
        reset_player()
        target = self.samples.get("series")
        if target is None:
            for name in ("season metadata", "start sampled episode", "startup <= 10s", "progress persistence / resume", "next episode"):
                self.record("SERIES", name, False, self.sample_error or "series sample unavailable")
            return
        season = int(target.get("season") or 1)
        episode = int(target.get("episode") or 1)
        status, payload, err = action("media.details", {"mediaId": target["mediaId"]})
        media = payload.get("media") if isinstance(payload, dict) else None
        counts = media.get("seasonEpisodeCounts") if isinstance(media, dict) else None
        season_ok = status == 200 and isinstance(counts, list) and len(counts) >= season
        self.record("SERIES", "season metadata", season_ok, "" if season_ok else (err or "seasonEpisodeCounts missing"))

        started_at = time.monotonic()
        ok, detail, _ = accepted_operation(
            "media.play",
            {"mediaId": target["mediaId"], "title": target["title"], "season": season, "episode": episode, "resume": False, "persist": True},
            timeout=STARTUP_LIMIT_SECONDS,
        )
        startup_elapsed = time.monotonic() - started_at
        d = diagnostics()
        snap = d.get("snapshot", {}).get("playback", {}) if isinstance(d, dict) else {}
        ep_ready = ok and snap.get("season") == season and snap.get("episode") == episode and snap.get("status") == "READY"
        self.record("SERIES", "start sampled episode", ep_ready, detail or f"state={snap.get('status')}, S={snap.get('season')}, E={snap.get('episode')}")
        self.record(
            "SERIES", "startup <= 10s", ep_ready and startup_elapsed <= STARTUP_LIMIT_SECONDS,
            f"elapsedSeconds={startup_elapsed:.2f}, limit={STARTUP_LIMIT_SECONDS:.2f}",
        )

        if not ep_ready:
            blocked = "blocked: sampled episode did not reach READY within startup budget"
            self.record("SERIES", "progress persistence / resume", False, blocked)
            self.record("SERIES", "next episode", False, blocked)
            return

        action("player.seek", {"positionMs": 90_000})
        time.sleep(0.8)
        action("player.pause")
        time.sleep(0.3)
        r_ok, r_detail, _ = accepted_operation(
            "media.play",
            {"mediaId": target["mediaId"], "title": target["title"], "season": season, "episode": episode, "resume": True, "persist": True},
            timeout=STARTUP_LIMIT_SECONDS,
        )
        rd = diagnostics()
        rs = rd.get("snapshot", {}).get("playback", {}) if isinstance(rd, dict) else {}
        pos = int(rs.get("positionMs") or 0)
        progress_ok = r_ok and 80_000 <= pos <= 120_000
        self.record("SERIES", "progress persistence / resume", progress_ok, r_detail or f"resumedPositionMs={pos}")

        expected_season, expected_episode = season, episode + 1
        if isinstance(counts, list) and 1 <= season <= len(counts):
            try:
                count = int(counts[season - 1])
            except Exception:
                count = 0
            if count > 0 and episode >= count and season < len(counts):
                expected_season, expected_episode = season + 1, 1
        n_ok, n_detail, _ = accepted_operation("player.nextEpisode", timeout=STARTUP_LIMIT_SECONDS)
        nd = diagnostics()
        ns = nd.get("snapshot", {}).get("playback", {}) if isinstance(nd, dict) else {}
        next_ok = n_ok and ns.get("season") == expected_season and ns.get("episode") == expected_episode and ns.get("status") == "READY"
        self.record("SERIES", "next episode", next_ok, n_detail or f"state={ns.get('status')}, S={ns.get('season')}, E={ns.get('episode')}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Movia one-command acceptance framework")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()
    summary = Runner(args.verbose).run()
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0 if summary["release_gate"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
