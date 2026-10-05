"""Movia-owned adapter for the public zona.mobi catalog and video API.

Observed protocol: exact search -> verified article -> exact episode -> video.
No APK loading, source-code imports, artificial voices or inferred HQ heights.
"""
from __future__ import annotations

import json
import math
import re
import tempfile
import logging
from concurrent.futures import ThreadPoolExecutor, wait
import subprocess
import time
from email.utils import parsedate_to_datetime
from urllib.parse import quote, urlparse

import requests

from catalog_schema_v2 import normalize_ru_text
from provider_contract import (
    ProviderArticle, ProviderDefinition, ProviderRequest, ProviderRequestProfile,
    ProviderSearchResult, VariantFolder, VariantStream,
)

logger = logging.getLogger("zona_mobi_provider_adapter")
_PROBE_EXECUTOR = ThreadPoolExecutor(max_workers=8, thread_name_prefix="movia-zona-media")
BASE = "https://android1.mzona.net/api/v1/"
DEFINITION = ProviderDefinition(
    provider_id="movia:zona-mobi", name="Zona.mobi", family="movia-rewrite",
    capabilities=frozenset({"movie", "series", "exact-identity", "exact-episode"}),
    request_profile=ProviderRequestProfile(base_urls=(BASE,)),
)


def client_timestamp(seconds: int, user_agent: str) -> int:
    """Public client timestamp checksum, computed without an external runtime."""
    value = 0
    encoded = (str(seconds) + user_agent).encode("utf-16-be")
    for offset in range(0, len(encoded), 2):
        value = (31 * value + int.from_bytes(encoded[offset:offset + 2], "big")) & 0xffffffff
    signed = value if value < 0x80000000 else value - 0x100000000
    # Java abs(MIN_VALUE) remains negative.
    absolute = signed if signed == -2147483648 else abs(signed)
    remainder = absolute % 1000 if absolute >= 0 else -((-absolute) % 1000)
    return seconds * 1000 + remainder


def _user_agent() -> str:
    def prop(name, fallback):
        try:
            return subprocess.check_output(["getprop", name], text=True, timeout=0.5).strip() or fallback
        except (OSError, subprocess.SubprocessError):
            return fallback
    maker = quote(prop("ro.product.manufacturer", "Android"), safe="")
    model = quote(prop("ro.product.model", "Movia"), safe="")
    release = prop("ro.build.version.release", "unknown")
    return f"Zona/1.10.2 ({maker}/{model}/Android {release})"


def duration_matches(actual, expected):
    try:
        actual, expected = float(actual), float(expected)
    except (TypeError, ValueError):
        return False
    return (math.isfinite(actual) and math.isfinite(expected) and expected > 0
            and 0.7 * expected <= actual <= 1.4 * expected)


def expected_duration(article):
    value = article.get("mobi_link_duration")
    if isinstance(value, (int, float)) and not isinstance(value, bool) and value > 0:
        return value / 1000
    runtime = article.get("runtime")
    value = runtime.get("value") if isinstance(runtime, dict) else None
    return value * 60 if isinstance(value, (int, float)) and not isinstance(value, bool) and value > 0 else None


def _positive_id(value):
    if isinstance(value, bool):
        return None
    text = str(value or "")
    return text if text.isascii() and text.isdigit() and int(text) > 0 else None


def _series(request):
    return request.media_type.casefold() in {"tv", "series", "serial", "tv_series"} or request.is_series_request


class ZonaMobiProviderAdapter:
    definition = DEFINITION

    def __init__(self, fetch=None, user_agent=None, probe=None):
        self.user_agent = user_agent or _user_agent()
        self.session = requests.Session()
        self.fetch = fetch or self._fetch
        self.probe = probe or self._probe
        self.deadline = time.monotonic() + 12

    def _fetch(self, path, params=None):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("PROVIDER_BUDGET")
        with self.session.get(
            BASE + path, params=params, headers={"User-Agent": self.user_agent},
            timeout=min(3, remaining), stream=True,
        ) as response:
            if path != "video/":
                response.raise_for_status()
            raw = bytearray()
            for chunk in response.iter_content(16384):
                raw.extend(chunk)
                if len(raw) > 2 * 1024 * 1024:
                    raise ValueError("RESPONSE_TOO_LARGE")
                if time.monotonic() >= self.deadline:
                    raise TimeoutError("PROVIDER_BUDGET")
            import json
            try:
                body = json.loads(raw) if raw else {}
            except ValueError:
                # The clock endpoint can return an empty/non-JSON response.
                if path != "video/":
                    raise
                body = {}
            return body, dict(response.headers)

    def _probe(self, url, expected):
        """Verify bounded metadata against the article, not merely an MP4 signature.

        Some live CDN replies are short placeholder containers. A successful
        HTTP response or ftyp atom is insufficient evidence for a movie leaf.
        """
        remaining = self.deadline - time.monotonic()
        if remaining <= 0 or expected is None:
            return None
        try:
            with requests.get(url, headers={"User-Agent": self.user_agent, "Range": "bytes=0-524287"},
                              timeout=min(2, remaining), stream=True) as response:
                if response.status_code not in {200, 206}:
                    return None
                raw = bytearray()
                for chunk in response.iter_content(16384):
                    raw.extend(chunk[:524288 - len(raw)])
                    if len(raw) >= 524288 or time.monotonic() >= self.deadline:
                        break
            if raw.startswith(b"#EXTM3U"):
                text = raw.decode("utf-8", errors="replace")
                if "#EXT-X-ENDLIST" not in text:
                    return None
                duration = sum(float(x) for x in re.findall(r"#EXTINF:([0-9.]+)", text))
                return {"transport": "hls", "duration": duration} if duration_matches(duration, expected) else None
            if b"ftyp" not in raw[:32]:
                return None
            with tempfile.NamedTemporaryFile(prefix="movia-zona-probe-", suffix=".mp4") as sample:
                sample.write(raw); sample.flush()
                result = subprocess.run(
                    ["ffprobe", "-v", "quiet", "-show_entries",
                     "format=duration:stream=codec_type,width,height", "-of", "json", sample.name],
                    capture_output=True, text=True, timeout=1.5,
                )
            if result.returncode:
                return None
            measured = json.loads(result.stdout)
            duration = float(measured.get("format", {}).get("duration", 0))
            videos = [x for x in measured.get("streams", []) if x.get("codec_type") == "video"
                      and isinstance(x.get("height"), int) and x["height"] > 0]
            if not videos or not duration_matches(duration, expected):
                return None
            video = videos[0]
            return {"transport": "direct", "duration": duration,
                    "height": video["height"], "width": video.get("width")}
        except (OSError, ValueError, subprocess.SubprocessError, requests.RequestException):
            return None

    def search(self, request: ProviderRequest, aliases=()):
        if _series(request) and not request.is_series_request:
            return [], "EXACT_EPISODE_REQUIRED"
        expected = {normalize_ru_text(x) for x in (request.title, *aliases) if x}
        try:
            data, _ = self.fetch("search/" + quote(request.title, safe=""), {"page": 1})
            rows = {}
            for item in data.get("items", []):
                if not isinstance(item, dict):
                    continue
                if normalize_ru_text(item.get("name_rus", "")) not in expected:
                    continue
                if item.get("serial") is not _series(request):
                    continue
                if request.year is not None and item.get("year") != request.year:
                    continue
                ident = _positive_id(item.get("mobi_link_id"))
                slug = item.get("name_id")
                if not ident or not isinstance(slug, str) or not slug:
                    continue
                rows[ident] = ProviderSearchResult(
                    DEFINITION, ident, item["name_rus"], item.get("year"), slug, ident,
                )
            logger.info("exact search media_id=%s matches=%s", request.media_id, len(rows))
            return list(rows.values()), None
        except Exception as exc:
            logger.info("search failed media_id=%s error=%s", request.media_id, type(exc).__name__)
            return [], "ZONA_MOBI_SEARCH_ERROR"

    def resolve_source(self, source, request):
        try:
            serial = _series(request)
            if serial and not request.is_series_request:
                return None, None, "EXACT_EPISODE_REQUIRED"
            path = ("tvseries/" if serial else "movies/") + quote(source.article_ref, safe="")
            data, article_headers = self.fetch(path)
            year = (data.get("release_date") or {}).get("year")
            if (
                data.get("name_id") != source.article_ref
                or _positive_id(data.get("mobi_link_id")) != source.item_id
                or normalize_ru_text(data.get("name_rus", "")) != normalize_ru_text(source.title)
                or data.get("serial") is not serial
                or (request.year is not None and year != request.year)
            ):
                return None, None, "ZONA_MOBI_IDENTITY_MISMATCH"
            video_id = source.item_id
            if serial:
                season, _ = self.fetch(path + "/season/" + str(request.season))
                matches = [
                    x for x in (season.get("episodes") or {}).get("items", [])
                    if isinstance(x, dict) and x.get("season") == request.season
                    and x.get("episode") == request.episode
                    and _positive_id(x.get("mobi_link_id"))
                ]
                if season.get("season_number") != request.season or len(matches) != 1:
                    return None, None, "ZONA_MOBI_EPISODE_MISMATCH"
                video_id = str(matches[0]["mobi_link_id"])
            date = next((v for k, v in article_headers.items() if k.casefold() == "date"), None)
            if not date:
                _, clock_headers = self.fetch("video/")
                date = next((v for k, v in clock_headers.items() if k.casefold() == "date"), None)
            seconds = int(parsedate_to_datetime(date).timestamp()) if date else int(time.time())
            video, _ = self.fetch("video/" + video_id, {
                "client_time": client_timestamp(seconds, self.user_agent),
            })
            leaves = []
            seen = set()
            # Each field is an actual returned transport. Labels are provider tiers,
            # not measured pixel resolutions; audio selection is absent in this API.
            tiers = (("url", "HQ"), ("lqUrl", "LQ"), ("lqHlsUrl", "LQ"), ("mqUrl", "MQ"))
            probes = {}
            for field, _ in tiers:
                url = video.get(field)
                if isinstance(url, str) and urlparse(url).scheme in {"http", "https"} and urlparse(url).hostname and url not in probes:
                    probes[url] = _PROBE_EXECUTOR.submit(self.probe, url, expected_duration(data))
            done, pending = wait(set(probes.values()), timeout=max(0, min(2.1, self.deadline - time.monotonic())))
            for future in pending:
                future.cancel()
            for field, tier in tiers:
                url = video.get(field)
                if not isinstance(url, str) or url in seen:
                    continue
                parsed = urlparse(url)
                if parsed.scheme not in {"http", "https"} or not parsed.hostname:
                    continue
                seen.add(url)
                future = probes.get(url)
                measured = future.result() if future in done else None
                if measured is None:
                    continue
                height = measured.get("height")
                quality = str(height) + "p" if isinstance(height, int) and height > 0 else tier
                leaves.append(VariantStream(
                    url=url, stream_key=video_id + ":" + field, label=tier,
                    quality=quality, season=request.season, episode=request.episode,
                    user_agent=self.user_agent, headers={"User-Agent": self.user_agent},
                    transport=measured["transport"],
                    transport_metadata={"provider_tier": tier, "video_id": video_id,
                                        "measured_duration_ms": int(measured["duration"] * 1000)},
                ))
            if not leaves:
                return None, None, "ZONA_MOBI_NO_VIDEO"
            article = ProviderArticle(
                DEFINITION, source.item_id, source.title, source.year,
                source.article_ref, source.content_ref,
            )
            tree = VariantFolder(season=request.season, episode=request.episode, children=tuple(leaves))
            logger.info("resolved media_id=%s episode=%s leaves=%s", request.media_id, request.episode_key, len(leaves))
            return tree, article, None
        except Exception as exc:
            logger.info("resolve failed media_id=%s error=%s", request.media_id, type(exc).__name__)
            return None, None, "ZONA_MOBI_PROVIDER_ERROR"
