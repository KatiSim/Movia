#!/usr/bin/env python3
"""Clean-room provider/parser boundary for Movia.

The provider side follows the reusable architecture recovered from LazyMedia
Deluxe: registry -> search/article -> hierarchical/lazy variant tree.  The
output side is deliberately StreamCandidate-compatible and follows Movia/Zona
playback semantics: exact content identity, stable logical source identity and
rich concrete stream metadata.

No provider credentials, cookies, signing material or provider-specific
algorithms belong in this module.
"""
from __future__ import annotations

import hashlib
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, Iterable, Mapping, Optional, Sequence, Union


def _text(value: Any) -> str:
    return str(value or "").strip()


def _stable_id(prefix: str, *parts: Any) -> str:
    payload = "\x1f".join(_text(part) for part in parts)
    return prefix + hashlib.sha256(payload.encode("utf-8")).hexdigest()[:24]


def _safe_track_index(value: Optional[int]) -> Optional[int]:
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int) or value < 0 or value > 2_147_483_647:
        raise ValueError("TRACK_INDEX")
    return value


@dataclass(frozen=True)
class ProviderRequest:
    media_id: str
    title: str
    year: Optional[int] = None
    season: Optional[int] = None
    episode: Optional[int] = None
    media_type: str = "movie"
    is_trailer: bool = False

    def __post_init__(self) -> None:
        if not _text(self.media_id):
            raise ValueError("MEDIA_ID")
        if not _text(self.title):
            raise ValueError("TITLE")
        if (self.season is None) != (self.episode is None):
            raise ValueError("EXACT_EPISODE_REQUIRED")
        if self.season is not None:
            if isinstance(self.season, bool) or isinstance(self.episode, bool):
                raise ValueError("EPISODE_COORDINATES")
            if int(self.season) <= 0 or int(self.episode) <= 0:
                raise ValueError("EPISODE_COORDINATES")

    @property
    def is_series_request(self) -> bool:
        return self.season is not None and self.episode is not None

    @property
    def episode_key(self) -> str:
        if not self.is_series_request:
            return "movie"
        return f"s{int(self.season)}e{int(self.episode)}"


@dataclass(frozen=True)
class ProviderRequestProfile:
    """Discovery/article request defaults, never implicit playback headers."""

    headers: Mapping[str, str] = field(default_factory=dict)
    user_agent: str = ""
    base_urls: tuple[str, ...] = ()
    aliases: tuple[str, ...] = ()
    properties: Mapping[str, str] = field(default_factory=dict)


@dataclass(frozen=True)
class ProviderDefinition:
    provider_id: str
    name: str
    family: str
    enabled: bool = True
    request_profile: ProviderRequestProfile = field(default_factory=ProviderRequestProfile)
    capabilities: frozenset[str] = field(default_factory=frozenset)
    source_type_id: Optional[int] = None
    content_type_id: Optional[int] = None

    def __post_init__(self) -> None:
        if not _text(self.provider_id) or not _text(self.name) or not _text(self.family):
            raise ValueError("PROVIDER_DEFINITION")


@dataclass(frozen=True)
class ProviderSearchResult:
    provider: ProviderDefinition
    item_id: str
    title: str
    year: Optional[int] = None
    article_ref: str = ""
    content_ref: str = ""

    def __post_init__(self) -> None:
        if not _text(self.item_id) or not _text(self.title):
            raise ValueError("SEARCH_RESULT")


@dataclass(frozen=True)
class ProviderArticle:
    provider: ProviderDefinition
    item_id: str
    title: str
    year: Optional[int] = None
    article_ref: str = ""
    content_ref: str = ""

    def __post_init__(self) -> None:
        if not _text(self.item_id) or not _text(self.title):
            raise ValueError("PROVIDER_ARTICLE")


class DeferredVariantLoader(ABC):
    """Provider adapter hook corresponding to LazyMedia's deferred folder parser."""

    @abstractmethod
    def load(
        self,
        folder: "VariantFolder",
        request: ProviderRequest,
    ) -> Sequence["VariantNode"]:
        raise NotImplementedError


@dataclass(frozen=True)
class VariantStream:
    url: str
    stream_key: str = ""
    label: str = ""
    voice: str = ""
    language: str = ""
    quality: str = ""
    resolution: str = ""
    season: Optional[int] = None
    episode: Optional[int] = None
    headers: Mapping[str, str] = field(default_factory=dict)
    user_agent: str = ""
    subtitles: tuple[Mapping[str, Any], ...] = ()
    has_internal_subtitles: bool = False
    video_track_index: Optional[int] = None
    audio_track_index: Optional[int] = None
    codec: str = ""
    mime_type: str = ""
    download_url: str = ""
    download_headers: Mapping[str, str] = field(default_factory=dict)
    reload_supported: bool = False
    reload_data: Any = None
    transport: str = "direct"
    seeders: Optional[int] = None
    info_hash: str = ""
    release_title: str = ""
    transport_metadata: Mapping[str, Any] = field(default_factory=dict)
    file_index: Optional[int] = None
    file_path: str = ""

    def __post_init__(self) -> None:
        if not _text(self.url):
            raise ValueError("STREAM_URL")
        _safe_track_index(self.video_track_index)
        _safe_track_index(self.audio_track_index)
        _safe_track_index(self.file_index)
        if (self.season is None) != (self.episode is None):
            raise ValueError("EXACT_EPISODE_REQUIRED")
        if self.seeders is not None:
            if isinstance(self.seeders, bool) or not isinstance(self.seeders, int) or self.seeders < 0:
                raise ValueError("SEEDERS")


@dataclass(frozen=True)
class VariantFolder:
    label: str = ""
    voice: str = ""
    quality: str = ""
    season: Optional[int] = None
    episode: Optional[int] = None
    children: Sequence["VariantNode"] = field(default_factory=tuple)
    loader: Optional[DeferredVariantLoader] = None

    def __post_init__(self) -> None:
        if self.episode is not None and self.season is None:
            raise ValueError("EPISODE_WITHOUT_SEASON")
        if self.season is not None and int(self.season) <= 0:
            raise ValueError("SEASON")
        if self.episode is not None and int(self.episode) <= 0:
            raise ValueError("EPISODE")


VariantNode = Union[VariantFolder, VariantStream]


@dataclass(frozen=True)
class _VariantContext:
    voice: str = ""
    quality: str = ""
    season: Optional[int] = None
    episode: Optional[int] = None

    def with_folder(self, folder: VariantFolder) -> "_VariantContext":
        return _VariantContext(
            voice=_text(folder.voice) or self.voice,
            quality=_text(folder.quality) or self.quality,
            season=folder.season if folder.season is not None else self.season,
            episode=folder.episode if folder.episode is not None else self.episode,
        )

    def with_stream(self, stream: VariantStream) -> "_VariantContext":
        return _VariantContext(
            voice=_text(stream.voice) or self.voice,
            quality=_text(stream.quality) or self.quality,
            season=stream.season if stream.season is not None else self.season,
            episode=stream.episode if stream.episode is not None else self.episode,
        )


def _branch_can_match(request: ProviderRequest, context: _VariantContext) -> bool:
    if request.is_series_request:
        if context.season is not None and context.season != request.season:
            return False
        if context.episode is not None and context.episode != request.episode:
            return False
        return True
    return context.season is None and context.episode is None


def _leaf_matches(request: ProviderRequest, context: _VariantContext) -> bool:
    if request.is_series_request:
        return context.season == request.season and context.episode == request.episode
    return context.season is None and context.episode is None


def _stream_row(
    article: ProviderArticle,
    request: ProviderRequest,
    stream: VariantStream,
    context: _VariantContext,
    branch_path: str,
) -> dict[str, Any]:
    voice = context.voice or "Не указано"
    quality = context.quality or "Не указано"
    stream_key = _text(stream.stream_key) or branch_path
    identity_parts = (
        article.provider.provider_id,
        article.item_id,
        request.episode_key,
        stream_key,
        voice,
        quality,
        _text(stream.language),
        str(stream.video_track_index) if stream.video_track_index is not None else "",
        str(stream.audio_track_index) if stream.audio_track_index is not None else "",
    )
    file_identity = ("file", str(stream.file_index) if stream.file_index is not None else "", stream.file_path) if stream.file_index is not None or stream.file_path else ()
    identity_parts += file_identity
    track_identity = ("tracks", str(stream.video_track_index) if stream.video_track_index is not None else "", str(stream.audio_track_index) if stream.audio_track_index is not None else "") if stream.video_track_index is not None or stream.audio_track_index is not None else ()
    provider_item_id = _stable_id("provider-item:", *identity_parts)
    logical_source_id = _stable_id(
        "logical-source:",
        article.provider.provider_id,
        article.item_id,
        request.episode_key,
        stream_key,
        voice,
        quality,
        *track_identity,
        *file_identity,
    )
    row: dict[str, Any] = {
        "source": article.provider.name,
        "provider": article.provider.name,
        "provider_id": article.provider.provider_id,
        "provider_item_id": provider_item_id,
        "logical_source_id": logical_source_id,
        "url": _text(stream.url),
        "voice": voice,
        "language": _text(stream.language) or "und",
        "quality": quality,
        "headers": dict(stream.headers),
        "transport": _text(stream.transport) or "direct",
        "catalog_media_id": request.media_id,
        "canonical_title": request.title,
        "canonical_year": request.year,
        "canonical_media_type": request.media_type,
        "is_trailer": bool(request.is_trailer),
    }
    if context.season is not None:
        row["season"] = context.season
    if context.episode is not None:
        row["episode"] = context.episode
    if article.provider.source_type_id is not None:
        row["source_type_id"] = article.provider.source_type_id
    if article.provider.content_type_id is not None:
        row["content_type_id"] = article.provider.content_type_id
    if stream.resolution:
        row["resolution"] = stream.resolution
    if stream.user_agent:
        row["user_agent"] = stream.user_agent
    if stream.subtitles:
        row["subtitle_list"] = [dict(item) for item in stream.subtitles]
    if stream.has_internal_subtitles:
        row["is_use_internal_subtitles"] = True
    if stream.file_index is not None:
        row["file_index"] = stream.file_index
    if stream.file_path:
        row["file_path"] = stream.file_path
    if stream.video_track_index is not None:
        row["video_track_index"] = stream.video_track_index
    if stream.audio_track_index is not None:
        row["audio_track_index"] = stream.audio_track_index
    if stream.codec:
        row["codec"] = stream.codec
    if stream.mime_type:
        row["mime_type"] = stream.mime_type
    if stream.download_url:
        row["download_url"] = stream.download_url
    if stream.download_headers:
        row["download_headers"] = dict(stream.download_headers)
    if stream.reload_supported:
        row["reload_supported"] = True
    if stream.reload_data is not None:
        row["reload_data"] = stream.reload_data
    if stream.seeders is not None:
        row["seeders"] = stream.seeders
    if stream.info_hash:
        row["info_hash"] = _text(stream.info_hash)
    if stream.release_title:
        row["title"] = _text(stream.release_title)
    if stream.transport_metadata:
        row["transport_metadata"] = dict(stream.transport_metadata)
    return row


def flatten_variant_tree(
    article: ProviderArticle,
    root: VariantNode,
    request: ProviderRequest,
    *,
    max_depth: int = 64,
    max_nodes: int = 20_000,
    max_streams: Optional[int] = None,
) -> list[dict[str, Any]]:
    """Resolve one exact request without eagerly expanding unrelated branches.

    Adapters provide already-structured dimensions. This shared layer never
    guesses season/episode/voice/quality from display text.
    """

    if max_depth < 1 or max_nodes < 1 or (max_streams is not None and max_streams < 1):
        raise ValueError("VARIANT_LIMITS")

    result: list[dict[str, Any]] = []
    visited: set[int] = set()
    visited_count = 0

    def walk(
        node: VariantNode,
        context: _VariantContext,
        path: str,
        depth: int,
    ) -> None:
        nonlocal visited_count
        if depth > max_depth or visited_count >= max_nodes:
            raise ValueError("VARIANT_LIMIT")
        marker = id(node)
        if marker in visited:
            return
        visited.add(marker)
        visited_count += 1

        if isinstance(node, VariantFolder):
            next_context = context.with_folder(node)
            if not _branch_can_match(request, next_context):
                return

            children: list[VariantNode] = list(node.children)
            if node.loader is not None:
                loaded = node.loader.load(node, request)
                if loaded:
                    children.extend(list(loaded))
            for index, child in enumerate(children):
                walk(child, next_context, f"{path}/{index}", depth + 1)
            return

        if not isinstance(node, VariantStream):
            return

        next_context = context.with_stream(node)
        if not _leaf_matches(request, next_context):
            return
        if max_streams is not None and len(result) >= max_streams:
            raise ValueError("VARIANT_LIMIT")
        result.append(_stream_row(article, request, node, next_context, path))

    walk(root, _VariantContext(), "root", 0)
    return result
