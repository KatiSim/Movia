#!/usr/bin/env python3
"""Movia-native Zona provider adapter for ProviderContract/VariantTree.

Only verified provider behavior is represented here. Network/protected-request
transport remains in Movia's zona_contract. This adapter owns identity, tree
composition, logging and candidate metadata. It never guesses a resolution.
"""
from __future__ import annotations

import json
import logging
import re
import sqlite3
from contextlib import closing
from pathlib import Path
from typing import Any, Optional

from catalog_schema_v2 import normalize_ru_text
from movia_paths import DATA_DIR
from provider_contract import (
    ProviderArticle, ProviderDefinition, ProviderRequest, ProviderRequestProfile,
    ProviderSearchResult, VariantFolder, VariantStream,
)
from zona_playback_architecture import transport_from_url

logger = logging.getLogger("zona_provider_adapter")

ZONA_PROVIDER = ProviderDefinition(
    provider_id="movia:zona",
    name="Zona",
    family="movia-rewrite",
    capabilities=frozenset({"movie", "series", "exact-identity", "voice", "quality", "reload"}),
    request_profile=ProviderRequestProfile(properties={"architecture": "provider-contract-variant-tree"}),
)


def _real_quality(value: Any) -> str:
    text = str(value or "").strip()
    if not text:
        return "Не указано"
    match = re.search(r"(?<!\d)(2160|1440|1080|720|576|480|360|240|144)p?\b", text, re.I)
    if match:
        return f"{match.group(1)}p"
    return "Не указано"


def _safe_index(value: Any) -> Optional[int]:
    if isinstance(value, bool): return None
    try: parsed = int(value)
    except (TypeError, ValueError): return None
    return parsed if parsed >= 0 else None


class ZonaProviderAdapter:
    definition = ZONA_PROVIDER

    def exact_catalog_result(self, request: ProviderRequest, *, db_path: Optional[Path] = None):
        path = Path(db_path) if db_path is not None else DATA_DIR / "catalog.db"
        try: media_id = int(str(request.media_id).strip())
        except (TypeError, ValueError): return None, "ZONA_MEDIA_ID_INVALID"
        try:
            with closing(sqlite3.connect(str(path), timeout=2.0)) as conn:
                conn.row_factory = sqlite3.Row
                row = conn.execute(
                    "SELECT id,title,original_title,year,media_type,tmdb_id FROM movies WHERE id=? LIMIT 1",
                    (media_id,),
                ).fetchone()
        except Exception as exc:
            logger.warning("Zona catalog identity lookup failed media_id=%s error=%s", request.media_id, type(exc).__name__)
            return None, "ZONA_CATALOG_LOOKUP_FAILED"
        if row is None: return None, "ZONA_CATALOG_ID_NOT_FOUND"
        expected = normalize_ru_text(request.title)
        titles = {normalize_ru_text(row["title"]), normalize_ru_text(row["original_title"])}
        if expected and expected not in titles:
            logger.warning("Zona identity rejected media_id=%s reason=title_mismatch", request.media_id)
            return None, "ZONA_IDENTITY_MISMATCH"
        if request.year and int(row["year"] or 0) != int(request.year):
            logger.warning("Zona identity rejected media_id=%s reason=year_mismatch", request.media_id)
            return None, "ZONA_IDENTITY_MISMATCH"
        logger.info("Zona exact identity accepted media_id=%s", request.media_id)
        return ProviderSearchResult(
            provider=self.definition,
            item_id=str(row["tmdb_id"] or row["id"]),
            title=str(row["title"] or request.title),
            year=int(row["year"] or 0) or request.year,
            article_ref=str(row["id"]),
            content_ref=str(row["original_title"] or ""),
        ), None

    def resolve_source(self, source: ProviderSearchResult, request: ProviderRequest, *, force_refresh: bool = False):
        from zona_contract import resolve_zona_for_title
        expected_titles = [request.title]
        if source.content_ref and normalize_ru_text(source.content_ref) != normalize_ru_text(request.title):
            expected_titles.append(source.content_ref)
        lookup = resolve_zona_for_title(
            title=request.title,
            expected_titles=expected_titles,
            year=request.year,
            media_type=request.media_type,
            season=request.season,
            episode=request.episode,
            force_refresh=force_refresh,
        )
        if lookup.status != "OK" or not lookup.streams:
            logger.info(
                "Zona VariantTree no result media_id=%s status=%s suggestions=%s refs=%s errors=%s",
                request.media_id, lookup.status, lookup.suggestions, lookup.source_refs, len(lookup.errors),
            )
            return None, None, f"ZONA_{lookup.status}"

        article_item = source.item_id
        article = ProviderArticle(
            provider=self.definition,
            item_id=article_item,
            title=source.title,
            year=source.year,
            article_ref=source.article_ref,
            content_ref=source.content_ref,
        )
        voice_groups: dict[str, list[VariantStream]] = {}
        for row in lookup.streams:
            url = str(row.get("url") or "").strip()
            if not url: continue
            voice = str(row.get("voice") or row.get("translation") or "Не указано").strip() or "Не указано"
            quality = _real_quality(row.get("quality") or row.get("resolution"))
            source_type = row.get("source_type_id") or row.get("video_source_type_id") or ""
            explicit_key = str(row.get("logical_source_id") or row.get("provider_item_id") or "").strip()
            selector = ("provider-key", explicit_key) if explicit_key else ("exact-locator", url, voice, quality)
            stream_key = json.dumps([
                str(source_type), str(row.get("provider_content_id") or row.get("provider_id") or ""),
                *selector,
            ], ensure_ascii=False, separators=(",", ":"))
            metadata = dict(row.get("transport_metadata") or {}) if isinstance(row.get("transport_metadata"), dict) else {}
            if source_type != "": metadata.setdefault("zona_source_type_id", str(source_type))
            leaf = VariantStream(
                url=url,
                stream_key=stream_key,
                voice=voice,
                language=str(row.get("language") or ""),
                quality=quality,
                resolution=str(row.get("resolution") or ""),
                season=request.season,
                episode=request.episode,
                headers=dict(row.get("headers") or {}) if isinstance(row.get("headers"), dict) else {},
                user_agent=str(row.get("user_agent") or ""),
                subtitles=tuple(dict(x) for x in (row.get("subtitle_list") or []) if isinstance(x, dict)),
                has_internal_subtitles=bool(row.get("is_use_internal_subtitles")),
                video_track_index=_safe_index(row.get("video_track_index")),
                audio_track_index=_safe_index(row.get("audio_track_index")),
                codec=str(row.get("codec") or ""),
                mime_type=str(row.get("mime_type") or ""),
                download_url=str(row.get("download_url") or ""),
                download_headers=dict(row.get("download_headers") or {}) if isinstance(row.get("download_headers"), dict) else {},
                reload_supported=bool(row.get("reload_supported") or row.get("reload_data") is not None),
                reload_data=row.get("reload_data"),
                transport=str(row.get("transport") or transport_from_url(url)),
                transport_metadata=metadata,
            )
            voice_groups.setdefault(voice, []).append(leaf)
        if not voice_groups:
            return None, None, "ZONA_NO_PLAYABLE_LEAVES"
        voices = tuple(VariantFolder(
            voice=voice, season=request.season, episode=request.episode, children=tuple(leaves)
        ) for voice, leaves in voice_groups.items())
        if request.is_series_request:
            root = VariantFolder(children=(VariantFolder(
                season=request.season,
                children=(VariantFolder(season=request.season, episode=request.episode, children=voices),),
            ),))
        else:
            root = VariantFolder(children=voices)
        logger.info("Zona VariantTree resolved media_id=%s voices=%s leaves=%s", request.media_id, len(voices), sum(len(x.children) for x in voices))
        return root, article, None
