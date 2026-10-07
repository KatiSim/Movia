#!/usr/bin/env python3
"""Movia-native torrent VariantTree adapter.

This module reimplements a verified provider behavior pattern: keep every
validated torrent as a concrete leaf and scope series under exact season/episode
folders. It uses only Movia resolver rows, ProviderContract types and Movia logs.
"""
from __future__ import annotations

import base64
import logging
import re
from collections import defaultdict
from typing import Any, Dict, Iterable, List, Tuple
from urllib.parse import unquote

from provider_contract import (
    ProviderArticle,
    ProviderDefinition,
    ProviderRequest,
    ProviderRequestProfile,
    VariantFolder,
    VariantStream,
    flatten_variant_tree,
)
from stream_validation import sanitize_streams, canonical_stream_locator

logger = logging.getLogger("torrent_provider_adapter")

_BTih = re.compile(r"(?i)(?:^|[?&])xt=urn:btih:([0-9a-f]{40}|[a-z2-7]{32})(?:&|$)")
_SAFE_PROVIDER = re.compile(r"[^a-z0-9._-]+")


def _provider_name(row: Dict[str, Any]) -> str:
    return str(row.get("provider") or row.get("source") or "Torrent").strip() or "Torrent"


def _provider_id(name: str) -> str:
    slug = _SAFE_PROVIDER.sub("-", name.casefold()).strip("-") or "torrent"
    return f"movia:torrent:{slug}"


def _info_hash(row: Dict[str, Any]) -> str:
    explicit = str(row.get("info_hash") or row.get("infoHash") or "").strip()
    url = str(row.get("url") or "").strip()
    match = _BTih.search(url)
    for value in (explicit, match.group(1) if match else ""):
        if re.fullmatch(r"[0-9a-fA-F]{40}", value):
            return value.casefold()
        if re.fullmatch(r"[A-Za-z2-7]{32}", value):
            return base64.b32decode(value.upper()).hex()
    return ""


def _release_title(row: Dict[str, Any]) -> str:
    return str(row.get("title") or row.get("release_title") or "").strip()


def _file_index(row: Dict[str, Any]):
    value = row.get("file_index", row.get("fileIndex"))
    if isinstance(value, bool) or value is None:
        return None
    try:
        index = int(value)
    except (TypeError, ValueError, OverflowError):
        return None
    if str(value).strip() != str(index) or not 0 <= index <= 2_147_483_647:
        return None
    return index


def _matches_bound_identity(row: Dict[str, Any], request: ProviderRequest) -> bool:
    for alias in ("catalog_media_id", "catalogMediaId"):
        if row.get(alias) not in (None, "") and str(row[alias]) != str(request.media_id):
            return False
    for alias in ("canonical_year", "canonicalYear"):
        if row.get(alias) not in (None, "") and request.year is not None:
            try:
                if int(row[alias]) != int(request.year):
                    return False
            except (TypeError, ValueError, OverflowError):
                return False
    return True


def _safe_seeders(value: Any) -> int:
    try:
        return max(0, int(value or 0))
    except (TypeError, ValueError, OverflowError):
        return 0


def _leaf_stream_key(row: Dict[str, Any], provider: str, index: int) -> str:
    info_hash = _info_hash(row)
    if info_hash:
        key = f"btih:{info_hash}"
        locator = canonical_stream_locator(str(row.get("url") or ""))
        if "|" in locator:
            key += "|" + locator.split("|", 1)[1]
        return key
    provider_item = str(row.get("provider_item_id") or row.get("providerItemId") or "").strip()
    if provider_item:
        return f"provider-item:{provider_item}"
    release = _release_title(row)
    if release:
        return f"release:{provider.casefold()}:{release.casefold()}"
    return f"row:{provider.casefold()}:{index}"


def _build_provider_tree(rows: List[Dict[str, Any]], request: ProviderRequest, provider: str) -> Tuple[ProviderArticle, VariantFolder]:
    definition = ProviderDefinition(
        provider_id=_provider_id(provider),
        name=provider,
        family="movia-rewrite-torrent",
        request_profile=ProviderRequestProfile(properties={"architecture": "provider-contract-variant-tree"}),
        capabilities=frozenset({"torrent", "voice", "quality", "exact-episode"}),
    )
    article = ProviderArticle(
        provider=definition,
        item_id=request.media_id,
        title=request.title,
        year=request.year,
        article_ref=request.media_id,
        content_ref=request.episode_key,
    )

    voice_groups: Dict[str, List[VariantStream]] = defaultdict(list)
    for index, row in enumerate(rows):
        url = str(row.get("url") or "").strip()
        if not url.startswith("magnet:?"):
            continue
        season = row.get("season")
        episode = row.get("episode")
        if request.is_series_request:
            # A card-level season pack is not an exact episode. Do not inherit
            # request coordinates into ambiguous torrent metadata.
            if season is None or episode is None:
                continue
            if int(season) != int(request.season) or int(episode) != int(request.episode):
                continue
        elif season is not None or episode is not None:
            continue
        voice = str(row.get("voice") or "Не указано").strip() or "Не указано"
        quality = str(row.get("quality") or "Не указано").strip() or "Не указано"
        info_hash = _info_hash(row)
        # Input has passed the common metadata/credential sanitizer.
        metadata = dict(row.get("transport_metadata") or {})
        metadata.update(torrent_provider=provider, release_title=_release_title(row))
        if row.get("size") is not None:
            metadata["size"] = row.get("size")
        voice_groups[voice].append(VariantStream(
            url=url,
            stream_key=_leaf_stream_key(row, provider, index),
            label=_release_title(row),
            voice=voice,
            quality=quality,
            language=str(row.get("language") or ""),
            resolution=str(row.get("resolution") or ""),
            audio_track_index=row.get("audio_track_index"),
            video_track_index=row.get("video_track_index"),
            file_index=_file_index(row),
            file_path=str(row.get("file_path") or row.get("filePath") or ""),
            headers=row.get("headers") or {},
            user_agent=str(row.get("user_agent") or ""),
            codec=str(row.get("codec") or ""),
            mime_type=str(row.get("mime_type") or row.get("mimeType") or ""),
            subtitles=tuple(row.get("subtitle_list") or ()),
            has_internal_subtitles=bool(row.get("is_use_internal_subtitles")),
            season=request.season,
            episode=request.episode,
            seeders=_safe_seeders(row.get("seeders") or row.get("seeds")),
            info_hash=info_hash,
            release_title=_release_title(row),
            transport="torrent_p2p",
            transport_metadata=metadata,
        ))

    voice_nodes = tuple(
        VariantFolder(
            voice=voice,
            season=request.season,
            episode=request.episode,
            children=tuple(leaves),
        )
        for voice, leaves in sorted(voice_groups.items(), key=lambda item: item[0].casefold())
        if leaves
    )
    if request.is_series_request:
        episode_folder = VariantFolder(
            label=f"Episode {request.episode}",
            season=request.season,
            episode=request.episode,
            children=voice_nodes,
        )
        root = VariantFolder(children=(VariantFolder(
            label=f"Season {request.season}",
            season=request.season,
            children=(episode_folder,),
        ),))
    else:
        root = VariantFolder(children=voice_nodes)
    return article, root


def rewrite_torrent_rows_as_variant_tree(rows: Iterable[Dict[str, Any]], request: ProviderRequest) -> List[Dict[str, Any]]:
    """Convert Movia-validated torrent rows into ProviderContract leaves.

    No provider lookup happens here. The caller keeps ownership of transport,
    retries, reliability and catalog search. This boundary only preserves
    provider/voice/quality/episode structure and stable torrent identity.
    """
    # Common torrent dedupe may collapse the same BTIH reported by mirrors.
    # Choose its provider deterministically instead of whichever row arrived first.
    ordered = sorted(list(rows), key=lambda row: (
        _provider_name(row).casefold(), str(row.get("url") or ""),
    ) if isinstance(row, dict) else ("", ""))
    clean = sanitize_streams(ordered, require_source=True)
    by_provider: Dict[str, List[Dict[str, Any]]] = defaultdict(list)
    for row in clean:
        if not _matches_bound_identity(row, request):
            logger.warning("Torrent bound identity mismatch media_id=%s", request.media_id)
            continue
        if not str(row.get("url") or "").startswith("magnet:?"):
            continue
        by_provider[_provider_name(row)].append(row)

    flattened: List[Dict[str, Any]] = []
    for provider, provider_rows in sorted(by_provider.items(), key=lambda item: item[0].casefold()):
        article, tree = _build_provider_tree(provider_rows, request, provider)
        flattened.extend(flatten_variant_tree(article, tree, request))
    flattened = sanitize_streams(flattened, require_source=True)
    logger.info(
        "Torrent VariantTree rewritten media_id=%s providers=%s input=%s leaves=%s",
        request.media_id,
        len(by_provider),
        len(clean),
        len(flattened),
    )
    return flattened
