#!/usr/bin/env python3
"""Movia-native Collaps adapter using the shared ProviderContract tree.

The provider behavior was independently reimplemented from observed/verified
behavior: exact catalog identity -> provider article/embed -> season/episode ->
concrete transport leaves. No LazyMedia source code, state, logs, credentials or
quality guesses are carried into this adapter.
"""
from __future__ import annotations

import json
import logging
import re
import sqlite3
from contextlib import closing
from pathlib import Path
from typing import Any, Callable, Dict, Optional, Tuple
from urllib.parse import urlsplit

from catalog_schema_v2 import normalize_ru_text
from movia_paths import DATA_DIR
from provider_contract import (
    ProviderArticle,
    ProviderDefinition,
    ProviderRequest,
    ProviderRequestProfile,
    ProviderSearchResult,
    VariantFolder,
    VariantStream,
)

logger = logging.getLogger("collaps_provider_adapter")

TextFetcher = Callable[[str, Dict[str, str]], Tuple[Optional[str], Optional[str]]]

COLLAPS_MIRRORS = (
    "https://api.delivembd.ws",
    "https://api.bhcesh.me",
    "https://api.apicollaps.cc",
)
COLLAPS_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
)

COLLAPS_PROVIDER = ProviderDefinition(
    provider_id="movia:collaps",
    name="Collaps",
    family="movia-rewrite",
    source_type_id=9,
    request_profile=ProviderRequestProfile(
        user_agent=COLLAPS_USER_AGENT,
        base_urls=COLLAPS_MIRRORS,
        properties={"architecture": "provider-contract-variant-tree"},
    ),
    capabilities=frozenset({"movie", "series", "exact-identity", "voice", "hls", "dash"}),
)


def _positive_int(value: Any) -> Optional[int]:
    if isinstance(value, bool):
        return None
    text = str(value or "").strip()
    if not text.isdecimal():
        return None
    parsed = int(text)
    return parsed if parsed > 0 else None


def _json_value(text: str, key: str) -> Any:
    match = re.search(r"(?<![\w])(?:[\"']?" + re.escape(key) + r"[\"']?)\s*:\s*", text)
    if not match:
        return None
    try:
        value, _ = json.JSONDecoder().raw_decode(text[match.end():].lstrip())
        return value
    except (TypeError, ValueError, json.JSONDecodeError):
        return None


def _transport_url(payload: Dict[str, Any], key: str) -> str:
    value = str(payload.get(key) or "").strip()
    if not value:
        return ""
    try:
        parsed = urlsplit(value)
    except ValueError:
        return ""
    return value if parsed.scheme in {"http", "https"} and parsed.hostname else ""


def _subtitle_rows(raw: Any) -> tuple[dict[str, Any], ...]:
    if not isinstance(raw, list):
        return ()
    result: list[dict[str, Any]] = []
    for item in raw[:64]:
        if isinstance(item, dict):
            url = str(item.get("url") or item.get("src") or "").strip()
            if url:
                result.append(dict(item))
        elif isinstance(item, str) and item.strip():
            result.append({"url": item.strip()})
    return tuple(result)


def _audio_names(payload: Dict[str, Any]) -> list[str]:
    audio = payload.get("audio")
    names = audio.get("names") if isinstance(audio, dict) else None
    if not isinstance(names, list) or not names:
        return ["Не указано"]
    result = [str(value or "").strip() or "Не указано" for value in names[:64]]
    counts: Dict[str, int] = {}
    for value in result:
        key = value.casefold()
        counts[key] = counts.get(key, 0) + 1
    seen: Dict[str, int] = {}
    display: list[str] = []
    for value in result:
        key = value.casefold()
        index = seen.get(key, 0) + 1
        seen[key] = index
        display.append(f"{value} · дорожка {index}" if counts[key] > 1 else value)
    return display


def _movie_payload(html: str) -> Dict[str, Any]:
    source = _json_value(html, "source")
    payload = dict(source) if isinstance(source, dict) else {}
    for key in ("hls", "dash"):
        if not payload.get(key):
            match = re.search(r"(?<![\w])(?:[\"']?" + key + r"[\"']?)\s*:\s*[\"'](https?://[^\"']+)[\"']", html)
            if match:
                payload[key] = match.group(1)
    if "audio" not in payload:
        payload["audio"] = _json_value(html, "audio")
    if "cc" not in payload:
        payload["cc"] = _json_value(html, "cc")
    return payload


def _voice_folders(
    payload: Dict[str, Any],
    *,
    mirror: str,
    request: ProviderRequest,
) -> tuple[VariantFolder, ...]:
    transports = [("hls", _transport_url(payload, "hls")), ("dash", _transport_url(payload, "dash"))]
    transports = [(kind, url) for kind, url in transports if url]
    if not transports:
        return ()
    voices = _audio_names(payload)
    subtitles = _subtitle_rows(payload.get("cc"))
    headers = {"User-Agent": COLLAPS_USER_AGENT, "Referer": mirror.rstrip("/") + "/"}
    folders: list[VariantFolder] = []
    has_explicit_audio = voices != ["Не указано"]
    for audio_index, voice in enumerate(voices):
        leaves: list[VariantStream] = []
        for kind, url in transports:
            leaves.append(VariantStream(
                url=url,
                stream_key=f"{kind}|audio:{audio_index}",
                label=kind.upper(),
                voice=voice,
                quality="Не указано",
                season=request.season,
                episode=request.episode,
                headers=headers,
                user_agent=COLLAPS_USER_AGENT,
                subtitles=subtitles,
                audio_track_index=audio_index if has_explicit_audio else None,
                transport=kind,
                mime_type="application/x-mpegURL" if kind == "hls" else "application/dash+xml",
                transport_metadata={"collaps_transport": kind},
            ))
        folders.append(VariantFolder(
            voice=voice,
            season=request.season,
            episode=request.episode,
            children=tuple(leaves),
        ))
    return tuple(folders)


def build_collaps_variant_tree(html: str, mirror: str, request: ProviderRequest) -> VariantFolder:
    if request.is_series_request:
        seasons = _json_value(html, "seasons")
        if not isinstance(seasons, list):
            return VariantFolder()
        wanted_season, wanted_episode = request.season, request.episode
        for season_row in seasons[:100]:
            if not isinstance(season_row, dict) or _positive_int(season_row.get("season")) != wanted_season:
                continue
            episodes = season_row.get("episodes")
            if not isinstance(episodes, list):
                continue
            for episode_row in episodes[:500]:
                if not isinstance(episode_row, dict) or _positive_int(episode_row.get("episode")) != wanted_episode:
                    continue
                voices = _voice_folders(episode_row, mirror=mirror, request=request)
                episode_folder = VariantFolder(
                    label=f"Episode {wanted_episode}",
                    season=wanted_season,
                    episode=wanted_episode,
                    children=voices,
                )
                return VariantFolder(children=(VariantFolder(
                    label=f"Season {wanted_season}",
                    season=wanted_season,
                    children=(episode_folder,),
                ),))
        return VariantFolder()
    return VariantFolder(children=_voice_folders(_movie_payload(html), mirror=mirror, request=request))


class CollapsProviderAdapter:
    definition = COLLAPS_PROVIDER

    def exact_catalog_result(self, request: ProviderRequest, *, db_path: Optional[Path] = None) -> tuple[Optional[ProviderSearchResult], Optional[str]]:
        path = Path(db_path) if db_path is not None else DATA_DIR / "catalog.db"
        try:
            media_id = int(str(request.media_id).strip())
        except (TypeError, ValueError):
            return None, "COLLAPS_MEDIA_ID_INVALID"
        try:
            with closing(sqlite3.connect(str(path), timeout=2.0)) as conn:
                conn.row_factory = sqlite3.Row
                row = conn.execute(
                    "SELECT id,title,original_title,year,media_type,imdb_id FROM movies WHERE id=? LIMIT 1",
                    (media_id,),
                ).fetchone()
        except Exception as exc:
            logger.warning("Collaps catalog identity lookup failed media_id=%s error=%s", request.media_id, type(exc).__name__)
            return None, "COLLAPS_CATALOG_LOOKUP_FAILED"
        if row is None:
            return None, "COLLAPS_CATALOG_ID_NOT_FOUND"
        expected = normalize_ru_text(request.title)
        titles = {normalize_ru_text(row["title"]), normalize_ru_text(row["original_title"])}
        if expected and expected not in titles:
            logger.warning("Collaps identity rejected media_id=%s reason=title_mismatch", request.media_id)
            return None, "COLLAPS_IDENTITY_MISMATCH"
        if request.year and int(row["year"] or 0) != int(request.year):
            logger.warning("Collaps identity rejected media_id=%s reason=year_mismatch", request.media_id)
            return None, "COLLAPS_IDENTITY_MISMATCH"
        imdb_id = str(row["imdb_id"] or "").strip()
        if not imdb_id:
            return None, "COLLAPS_IMDB_ID_MISSING"
        logger.info("Collaps exact identity accepted media_id=%s imdb=%s", request.media_id, imdb_id)
        return ProviderSearchResult(
            provider=self.definition,
            item_id=imdb_id,
            title=str(row["title"] or request.title),
            year=int(row["year"] or 0) or request.year,
            article_ref=imdb_id,
            content_ref=str(request.media_id),
        ), None

    def resolve_source(
        self,
        source: ProviderSearchResult,
        request: ProviderRequest,
        *,
        fetch_text: Optional[TextFetcher],
    ) -> tuple[Optional[VariantFolder], Optional[ProviderArticle], Optional[str]]:
        if fetch_text is None:
            return None, None, "COLLAPS_REQUEST_UNAVAILABLE"
        imdb_id = str(source.item_id or "").strip()
        if not imdb_id:
            return None, None, "COLLAPS_SOURCE_REF_INCOMPLETE"
        errors: list[str] = []
        for mirror in COLLAPS_MIRRORS:
            url = f"{mirror}/embed/imdb/{imdb_id}"
            headers = {"User-Agent": COLLAPS_USER_AGENT, "Accept": "text/html,*/*;q=0.8"}
            try:
                body, error = fetch_text(url, headers)
            except Exception as exc:
                body, error = None, type(exc).__name__
            if error or not isinstance(body, str) or not body.strip():
                errors.append(str(error or "EMPTY_RESPONSE")[:80])
                logger.debug("Collaps article mirror failed host=%s error=%s", mirror, error or "EMPTY_RESPONSE")
                continue
            tree = build_collaps_variant_tree(body, mirror, request)
            if not tree.children:
                errors.append("NO_PLAYABLE_VARIANTS")
                logger.debug("Collaps article had no exact variants host=%s media_id=%s", mirror, request.media_id)
                continue
            article = ProviderArticle(
                provider=self.definition,
                item_id=imdb_id,
                title=source.title,
                year=source.year,
                article_ref=url,
                content_ref=source.content_ref,
            )
            logger.info("Collaps VariantTree resolved media_id=%s imdb=%s root_children=%s", request.media_id, imdb_id, len(tree.children))
            return tree, article, None
        suffix = ":" + ";".join(errors[:3]) if errors else ""
        logger.warning("Collaps VariantTree failed media_id=%s mirrors=%s", request.media_id, len(COLLAPS_MIRRORS))
        return None, None, "COLLAPS_ARTICLE_FAILED" + suffix
