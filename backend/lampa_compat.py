#!/usr/bin/env python3
"""Clean-room compatibility helpers for the public LAMPA/Lampac result shape.

This module only translates metadata and playback locators. It does not copy
provider parsers, scrape third-party sites, or carry credentials between
applications. A tracker-page Link is deliberately kept out of url:
only an explicit media URL or a magnet is playable.
"""
from __future__ import annotations

import re
from typing import Any, Dict, List, Optional
from urllib.parse import quote


# These are display aliases, not provider implementations. The list is small
# and intentionally limited to names already used by the Movia UI contract.
_VOICE_ALIASES = (
    ("Кубик в Кубе", ("кубик в кубе", "kubik v kube", "kubik")),
    ("LostFilm", ("lostfilm", "лостфильм")),
    ("HDRezka", ("hdrezka", "rezka", "резка")),
    ("Red Head Sound", ("red head sound", "redheadsound", "rhs", "ред хед", "редхед")),
    ("AlexFilm", ("alexfilm", "alex film", "алексфильм")),
    ("NewStudio", ("newstudio", "ньюстудио")),
    ("Flarrow Films", ("flarrow films", "flarrow", "флэроу")),
    ("Jaskier", ("jaskier", "яскьер", "джаскьер")),
    ("TVShows", ("tvshows", "твшоус")),
    ("Кураж-Бамбей", ("кураж-бамбей", "кураж бамбей", "kuraj")),
    ("LE-Vitation", ("le-vitation", "levitation", "левитейшн")),
    ("Пифагор (Дубляж)", ("пифагор", "pythagor")),
    ("Сыендук", ("сыендук", "syenduk")),
    ("Кравец", ("кравец", "kravec", "kravets")),
    ("2x2", ("2x2", "2х2")),
    ("Чистый звук (Line)", ("чистый звук", "line audio", "line", "звук с ts")),
    ("Дубляж", ("дубляж", "дублированный", "dub", "полное дублирование")),
    ("Профессиональный (МВО)", ("многоголосый", "профессиональный", "проф.", "мво", "mvo")),
    ("Двухголосый (ДВО)", ("двухголосый", "дво", "dvo")),
    ("Авторский (Одноголосый)", ("авторский", "одноголосый", "пво", "гоблин")),
    ("Original (с субтитрами)", ("original", "english", "оригинал", "subbed")),
)


def _casefold_key_map(value: Any) -> Dict[str, Any]:
    if not isinstance(value, dict):
        return {}
    return {str(key).casefold(): item for key, item in value.items()}


def _first_value(container: Any, *names: str) -> Any:
    mapped = _casefold_key_map(container)
    for name in names:
        value = mapped.get(name.casefold())
        if value is not None and (not isinstance(value, str) or value.strip()):
            return value
    return None


def _text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value.strip()
    if isinstance(value, (list, tuple, set)):
        parts: List[str] = []
        for item in value:
            item_text = _text(item)
            if item_text and item_text not in parts:
                parts.append(item_text)
        return ", ".join(parts)
    if isinstance(value, dict):
        for key in ("name", "title", "label", "value", "voice", "translation"):
            item = _first_value(value, key)
            item_text = _text(item)
            if item_text:
                return item_text
        return ""
    return str(value).strip()


def _first_text(container: Any, *names: str) -> str:
    return _text(_first_value(container, *names))


def _positive_int(value: Any, *, maximum: int = 10000) -> Optional[int]:
    if isinstance(value, bool):
        return None
    try:
        parsed = int(float(str(value).strip()))
    except (TypeError, ValueError, OverflowError):
        return None
    if parsed < 0 or parsed > maximum:
        return None
    return parsed


def _valid_btih(value: Any) -> bool:
    text = _text(value)
    return bool(
        re.fullmatch(r"[0-9a-fA-F]{40}", text)
        or re.fullmatch(r"[A-Z2-7a-z2-7]{32}", text)
    )


def _extract_btih(value: Any) -> str:
    text = _text(value)
    match = re.search(r"(?:xt=urn:btih:|btih:)([A-Za-z0-9]{32,40})", text, re.IGNORECASE)
    candidate = match.group(1) if match else ""
    return candidate if _valid_btih(candidate) else ""


def _contains_marker(text: str, marker: str) -> bool:
    lowered = text.casefold()
    marker = marker.casefold().strip()
    if not marker:
        return False
    if " " in marker or "-" in marker or any(ord(char) > 127 for char in marker):
        return marker in lowered
    return bool(re.search(r"(?<![\w])" + re.escape(marker) + r"(?![\w])", lowered))


def _detected_voices(text: str) -> List[str]:
    if not text:
        return []
    found: List[str] = []
    for canonical, aliases in _VOICE_ALIASES:
        if any(_contains_marker(text, alias) for alias in aliases):
            found.append(canonical)
    return found


def normalize_voice(value: Any) -> str:
    """Return a compact display label for an explicit LAMPA voice field."""
    text = _text(value)
    if not text or text.casefold() in {"не указано", "unknown", "n/a"}:
        return ""
    detected = _detected_voices(text)
    if detected:
        return ", ".join(detected)
    return text[:200]


def _part_numbers(title: str) -> tuple[set[int], set[int]]:
    seasons: set[int] = set()
    episodes: set[int] = set()

    # S01E02, S01.E02, and the compact form used by most torrent releases.
    for match in re.finditer(
        r"(?i)(?<![\w])s0*(\d{1,2})[ ._-]*e0*(\d{1,3})(?!\d)",
        title,
    ):
        seasons.add(int(match.group(1)))
        episodes.add(int(match.group(2)))

    # 1x02 is also common in LAMPA's episode parser.
    for match in re.finditer(
        r"(?<![\w])(\d{1,2})[ ._-]*x[ ._-]*0*(\d{1,3})(?!\d)",
        title,
        re.IGNORECASE,
    ):
        seasons.add(int(match.group(1)))
        episodes.add(int(match.group(2)))

    # A pack can advertise a range, for example S01-S04 or Сезоны 1-4.
    for match in re.finditer(
        r"(?i)(?<![\w])s0*(\d{1,2})[ ._-]*-[ ._-]*s?0*(\d{1,2})(?!\d)",
        title,
    ):
        start, end = int(match.group(1)), int(match.group(2))
        if 0 < start <= end <= 99:
            seasons.update(range(start, end + 1))
    for match in re.finditer(
        r"(?iu)\bсезон(?:ы|а|ов)?\s*0*(\d{1,2})\s*[-–—]\s*0*(\d{1,2})(?!\d)",
        title,
    ):
        start, end = int(match.group(1)), int(match.group(2))
        if 0 < start <= end <= 99:
            seasons.update(range(start, end + 1))

    for match in re.finditer(r"(?i)(?<![\w])s0*(\d{1,2})(?![ ._-]*e?\d)", title):
        seasons.add(int(match.group(1)))
    for match in re.finditer(
        r"(?iu)\bсезон(?:а|ов)?\s*0*(\d{1,2})(?!\d)", title
    ):
        seasons.add(int(match.group(1)))

    for match in re.finditer(
        r"(?iu)(?<![\w])(?:e|ep|episode|серия|эпизод)[ ._-]*0*(\d{1,3})(?!\d)",
        title,
    ):
        episodes.add(int(match.group(1)))
    if not episodes:
        for match in re.finditer(
            r"(?iu)\b0*(\d{1,3})\s*(?:серия|эпизод)\b",
            title,
        ):
            episodes.add(int(match.group(1)))

    # LAMPA also accepts a bare [1] episode marker. Avoid treating a season
    # range or a four-digit year as an episode.
    if not episodes:
        for match in re.finditer(r"(?<!\d)\[0*(\d{1,3})\](?!\d)", title):
            episodes.add(int(match.group(1)))

    seasons = {value for value in seasons if 0 < value <= 99}
    episodes = {value for value in episodes if 0 < value <= 999}
    return seasons, episodes


def _quality_from_title(title: str) -> str:
    if re.search(r"(?i)(?<![\w])(?:2160p?|4k|uhd|ultra[ ._-]*hd)(?![\w])", title):
        return "4K"
    for height in (1080, 720, 576, 480, 360):
        if re.search(r"(?i)(?<!\d)" + str(height) + r"p?(?!\d)", title):
            return f"{height}p"
    if re.search(r"(?i)\b(?:remux|bdremux)\b", title):
        return "Remux"
    if re.search(r"(?i)\b(?:web[ ._-]*dl|web[ ._-]*rip)\b", title):
        return "WEB-DL"
    if re.search(r"(?i)\b(?:bdrip|hdrip|dvdrip)\b", title):
        return "BDRip"
    if re.search(r"(?i)\b(?:camrip|telesync|cam)\b", title):
        return "TS / CAM"
    return ""


def _resolution_from_title(title: str) -> str:
    match = re.search(r"(?<!\d)(\d{3,4})[xх](\d{3,4})(?!\d)", title)
    if match:
        return f"{int(match.group(1))}x{int(match.group(2))}"
    return ""


def _hdr_from_title(title: str) -> str:
    if re.search(r"(?i)\b(?:dolby[ ._-]*vision|dv)\b", title):
        return "Dolby Vision"
    if re.search(r"(?i)\bhdr10\+\b", title):
        return "HDR10+"
    if re.search(r"(?i)\bhdr10\b", title):
        return "HDR10"
    if re.search(r"(?i)\bhdr\b", title):
        return "HDR"
    return ""


def parse_lampa_release_title(title: Any) -> Dict[str, Any]:
    """Extract release facts used by LAMPA's torrent and voice UI."""
    text = _text(title)
    seasons, episodes = _part_numbers(text)
    years = [int(match.group(0)) for match in re.finditer(r"(?<![\dxх])(?:19|20)\d{2}(?![\dxх])", text)]
    return {
        "season": next(iter(seasons)) if len(seasons) == 1 else None,
        "episode": next(iter(episodes)) if len(episodes) == 1 else None,
        "seasons": sorted(seasons),
        "episodes": sorted(episodes),
        "year": years[0] if years else None,
        "quality": _quality_from_title(text),
        "resolution": _resolution_from_title(text),
        "hdr": _hdr_from_title(text),
        "voices": _detected_voices(text),
    }


def _magnet_from_hash(info_hash: str, title: str) -> str:
    if not info_hash:
        return ""
    suffix = "&dn=" + quote(title, safe="") if title else ""
    return "magnet:?xt=urn:btih:" + info_hash + suffix


def _probe_has_internal_subtitles(probe: Any) -> bool:
    if not isinstance(probe, dict):
        return False
    streams = _first_value(probe, "streams")
    if not isinstance(streams, list):
        return False
    for item in streams:
        if isinstance(item, dict) and _first_text(item, "codec_type", "codecType").casefold() == "subtitle":
            return True
    return False


def normalize_lampa_result(raw: Any) -> Dict[str, Any]:
    """Translate a LAMPA/Lampac result into Movia's safe stream shape."""
    if not isinstance(raw, dict):
        return {}

    result = dict(raw)
    info = _first_value(raw, "Info", "info")
    if not isinstance(info, dict):
        info = {}

    title = (
        _first_text(raw, "title", "Title", "name", "release_name", "releaseName")
        or _first_text(info, "name", "title", "originalname", "originalName")
    )
    magnet_or_url = (
        _first_text(raw, "url", "playback_url", "playbackUrl", "stream_url", "streamUrl")
        or _first_text(raw, "MagnetUri", "magnetUri", "magnet_url", "magnetUrl", "magnet")
    )
    tracker_link = _first_text(raw, "Link", "link")
    if not magnet_or_url and tracker_link.casefold().startswith("magnet:?"):
        magnet_or_url = tracker_link

    info_hash = (
        _first_text(raw, "info_hash", "infoHash", "InfoHash", "hash")
        or _extract_btih(magnet_or_url)
    )
    if info_hash and not _valid_btih(info_hash):
        info_hash = ""
    if not magnet_or_url and info_hash:
        magnet_or_url = _magnet_from_hash(info_hash, title)

    if title:
        result["title"] = title
    if magnet_or_url:
        result["url"] = magnet_or_url
    if info_hash:
        result["info_hash"] = info_hash

    source = (
        _first_text(raw, "source", "source_id", "sourceId", "provider", "provider_id", "providerId")
        or _first_text(raw, "Tracker", "tracker")
        or _first_text(info, "tracker", "source", "provider")
    )
    if source:
        result["source"] = source
    else:
        # Do not fabricate a provider identity for arbitrary generic URLs.
        # A source-less candidate is accepted as LAMPA only when the input
        # actually carries a LAMPA/Lampac-specific result shape.
        lampa_shape = any(
            key in raw
            for key in (
                "Tracker", "tracker", "Info", "info",
                "MagnetUri", "magnetUri", "InfoHash", "infoHash",
            )
        )
        if lampa_shape:
            result["source"] = "LAMPA"

    explicit_voice = (
        _first_value(raw, "voice", "Voice", "translation", "Translation", "voices", "Voices")
        or _first_value(info, "voices", "voice", "translation", "Voice", "Translation")
    )
    voice = normalize_voice(explicit_voice)
    if not voice and title:
        detected = parse_lampa_release_title(title)["voices"]
        voice = ", ".join(detected)
    if voice:
        result["voice"] = voice

    explicit_quality = (
        _first_text(raw, "quality", "Quality", "resolution", "Resolution")
        or _first_text(info, "quality", "resolution", "video_type", "videotype")
    )
    if explicit_quality:
        result["quality"] = explicit_quality
    elif title:
        parsed = parse_lampa_release_title(title)
        if parsed["quality"]:
            result["quality"] = parsed["quality"]
        if parsed["resolution"]:
            result["resolution"] = parsed["resolution"]
        if parsed["hdr"]:
            result["hdr"] = parsed["hdr"]
    if title:
        parsed = parse_lampa_release_title(title)
        if parsed["resolution"] and not result.get("resolution"):
            result["resolution"] = parsed["resolution"]
        if parsed["hdr"] and not result.get("hdr"):
            result["hdr"] = parsed["hdr"]

    raw_seeders = _first_value(raw, "seeders", "Seeds", "Seeders", "seeds", "sid")
    if raw_seeders is None:
        raw_seeders = _first_value(info, "seeders", "Seeds", "Seeders", "seeds", "sid")
    seeders = _positive_int(raw_seeders)
    if seeders is not None:
        result["seeders"] = seeders
    raw_peers = _first_value(raw, "peers", "Peers", "pir")
    if raw_peers is None:
        raw_peers = _first_value(info, "peers", "Peers", "pir")
    peers = _positive_int(raw_peers)
    if peers is not None:
        result["peers"] = peers

    raw_season = _first_value(raw, "season", "Season")
    if raw_season is None:
        raw_season = _first_value(info, "season", "Season")
    raw_episode = _first_value(raw, "episode", "Episode")
    if raw_episode is None:
        raw_episode = _first_value(info, "episode", "Episode")
    season = _positive_int(raw_season, maximum=99)
    episode = _positive_int(raw_episode, maximum=999)
    if season == 0:
        season = None
    if episode == 0:
        episode = None
    parsed = parse_lampa_release_title(title) if title else {}
    if season is None:
        season = parsed.get("season")
    if episode is None:
        episode = parsed.get("episode")
    if season is not None:
        result["season"] = season
    if episode is not None:
        result["episode"] = episode
    if parsed.get("seasons") and not _first_value(raw, "seasons", "Seasons"):
        result["seasons"] = parsed["seasons"]
    if parsed.get("episodes") and not _first_value(raw, "episodes", "Episodes"):
        result["episodes"] = parsed["episodes"]

    raw_file_index = _first_value(raw, "file_index", "fileIndex")
    if raw_file_index is None:
        raw_file_index = _first_value(info, "file_index", "fileIndex")
    file_index = _positive_int(raw_file_index, maximum=1000000)
    if file_index is not None:
        result["file_index"] = file_index

    probe = _first_value(raw, "ffprobe", "FFProbe", "probe")
    if probe is None:
        probe = _first_value(info, "ffprobe", "FFProbe", "probe")
    if _probe_has_internal_subtitles(probe):
        result["is_use_internal_subtitles"] = True

    if magnet_or_url.casefold().startswith("magnet:?") or info_hash:
        result.setdefault("transport", "torrent")
    if tracker_link and not tracker_link.casefold().startswith("magnet:?"):
        # Metadata only; stream_validation does not expose this as the playable URL.
        result.setdefault("source_url", tracker_link)

    return result
