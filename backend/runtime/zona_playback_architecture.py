#!/usr/bin/env python3
"""Clean-room playback contract recovered from Zona 3.0.68 V6 evidence.

This module contains only architecture and non-secret semantics that were
verified from serializer bytecode / extractor registry / Media3 probe callsites.
It intentionally contains no provider credentials, cookies, signing material,
or protected access-control logic.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, Mapping, Optional
from urllib.parse import urlparse


@dataclass(frozen=True)
class VideoSourceRef:
    """Exact client-side VideoSource DTO shape recovered from C3912Z serializer."""

    id: int = 0
    video_source_type_id: int = 0
    video_content_type_id: int = 0
    kinopoisk_id: int = 0
    download_link_key: str = ""
    episode_key: str = ""
    info: str = ""

    @classmethod
    def from_mapping(cls, raw: Mapping[str, Any]) -> "VideoSourceRef":
        def first(*keys: str) -> Any:
            for key in keys:
                if key in raw and raw.get(key) is not None:
                    return raw.get(key)
            return None

        def as_int(value: Any) -> int:
            try:
                return int(value or 0)
            except (TypeError, ValueError, OverflowError):
                return 0

        def as_text(value: Any) -> str:
            return str(value or "")

        return cls(
            id=as_int(first("id", "source_id", "sourceId", "video_source_id", "videoSourceId")),
            video_source_type_id=as_int(first(
                "video_source_type_id", "videoSourceTypeId", "source_type_id", "sourceTypeId", "type_id", "typeId",
            )),
            video_content_type_id=as_int(first(
                "video_content_type_id", "videoContentTypeId", "content_type_id", "contentTypeId",
            )),
            kinopoisk_id=as_int(first("kinopoisk_id", "kinopoiskId", "kp_id", "kpId")),
            download_link_key=as_text(first(
                "download_link_key", "downloadLinkKey", "download_key", "downloadKey", "source_key", "sourceKey", "key",
            )),
            episode_key=as_text(first("episode_key", "episodeKey")),
            info=as_text(first("info")),
        )


# Ground-truth registry from reports/REGISTRY_GROUND_TRUTH.tsv.
ZONA_SOURCE_REGISTRY: Dict[int, str] = {
    1: "Zona MobiLink",
    2: "HDRezka",
    3: "Filmix",
    5: "Bazon",
    6: "SvetaCDN / VideoCDN",
    7: "Kinomania",
    8: "Alloha / NewPlay",
    9: "Collaps / AWM",
    10: "Bazon Mirror (CZX)",
    11: "UStore / UBoost",
    12: "Lordfilms",
    13: "SmotrimKino / ImperialFilm",
    14: "Kino-Teatr",
    15: "Kodik",
    16: "VideoFrame Space",
    17: "Kinovod",
    19: "Sarnage / ZetFlix CDN",
    20: "Sarnage Mirror",
    21: "IVI Series",
    22: "IVI Movies Proxy",
    23: "Oveg",
    24: "Zagonka",
    25: "ZetFlix App",
    26: "FreeKinoPlay / HDVB",
    27: "Playep / RedHeadSound",
    28: "Voidboost",
    29: "The-Film",
    30: "Anwap Mobile",
    31: "VK Video",
    32: "LinkToDo",
    33: "CDNVideohub",
    34: "FotPro / CDNMovies",
    35: "FanCDN",
    36: "Film.ru",
    37: "VideoFrame 2",
    38: "Mail.ru Cloud / MegaOblako",
    39: "SoopLive",
    40: "KinoSerial",
    41: "Obrut Show",
    42: "RuTube",
    43: "PLVideo (Platforma)",
    44: "Lomont",
    45: "RstPrg",
    46: "OK (Odnoklassniki)",
    47: "FlixCDN",
    48: "KinoVibe",
    49: "Generic Direct URL",
    50: "KinoTon",
    51: "KinoBadi (Scraper, NOT P2P)",
    52: "FanSerials",
    53: "VideoDB Cloud",
}

# Verified source-type capabilities. These are behavior flags, not credentials.
ZONA_MEDIA_PROBE_SOURCE_TYPES = frozenset({23, 32, 45, 53})
ZONA_WEB_LOADER_SOURCE_TYPES = frozenset({5, 17, 24, 40, 41, 47})
ZONA_HLS_SOURCE_TYPES = frozenset({8, 11, 13, 17, 19, 20, 24, 27, 29, 31, 32, 34, 35, 37, 43, 52, 53})
ZONA_DASH_SOURCE_TYPES = frozenset({23, 32})
ZONA_P2P_SOURCE_TYPES = frozenset()


def extractor_label(source_type_id: int) -> str:
    return ZONA_SOURCE_REGISTRY.get(int(source_type_id), f"extractor-{int(source_type_id)}")


def transport_from_url(url: str) -> str:
    value = str(url or "").strip()
    lower = value.casefold()
    try:
        path = (urlparse(value).path or "").casefold()
    except Exception:
        path = ""
    if lower.startswith("magnet:?"):
        return "torrent_p2p"
    if path.endswith(".m3u8") or "/hls/" in path:
        return "hls"
    if path.endswith(".mpd"):
        return "dash"
    return "direct"


def source_capabilities(source_type_id: int) -> Dict[str, Any]:
    source_type_id = int(source_type_id)
    return {
        "source_type_id": source_type_id,
        "provider_label": extractor_label(source_type_id),
        "media_probe": "ALL" if source_type_id in ZONA_MEDIA_PROBE_SOURCE_TYPES else "",
        "web_loader": source_type_id in ZONA_WEB_LOADER_SOURCE_TYPES,
        "hls": source_type_id in ZONA_HLS_SOURCE_TYPES,
        "dash": source_type_id in ZONA_DASH_SOURCE_TYPES,
        "p2p": False,
    }


@dataclass
class LegacyStreamBuilder:
    """C3894G-compatible builder mapped to the 22-field StreamInfo contract."""

    video_source: VideoSourceRef
    url: str
    translation: str = ""
    language: str = ""
    quality: str = "MEDIUM"
    resolution: str = ""
    subtitle_list: list[Dict[str, Any]] = field(default_factory=list)
    is_use_internal_subtitles: bool = False
    headers: Dict[str, str] = field(default_factory=dict)
    is_trailer: bool = False
    codec: str = ""
    download_url: str = ""
    download_format: str = ""
    download_headers: Optional[Dict[str, str]] = None
    skip_intervals: list[Dict[str, Any]] = field(default_factory=list)
    video_track_index: int = -1
    audio_track_index: int = -1
    advertisement: Any = None
    reload_data: Any = None
    duration: int = 0
    size: int = 0
    provider_label: str = ""
    transport_metadata: Dict[str, str] = field(default_factory=dict)

    def to_stream_dict(self) -> Dict[str, Any]:
        headers = dict(self.headers)
        download_headers = dict(self.download_headers if self.download_headers is not None else headers)
        user_agent = next(
            (str(value) for name, value in headers.items() if str(name).casefold() == "user-agent"),
            "",
        )
        result: Dict[str, Any] = {
            "source": "Zona",
            "provider": self.provider_label or extractor_label(self.video_source.video_source_type_id),
            "provider_item_id": self.video_source.id or self.video_source.video_source_type_id,
            "source_type_id": self.video_source.video_source_type_id,
            "content_type_id": self.video_source.video_content_type_id,
            "url": self.url,
            "voice": self.translation or "Не указано",
            "translation": self.translation or "",
            "language": self.language or "",
            "quality": self.quality or "MEDIUM",
            "resolution": self.resolution or "",
            "subtitle_list": list(self.subtitle_list),
            "is_use_internal_subtitles": bool(self.is_use_internal_subtitles),
            "user_agent": user_agent,
            "headers": headers,
            # C3895H -> old C3892E overload hardcodes this field false.
            "unavailable_quality": False,
            "is_trailer": bool(self.is_trailer),
            "codec": self.codec or "",
            # V6 ground truth: only MP4 download format becomes downloadUrl.
            "download_url": self.download_url if self.download_format.casefold() == "mp4" else "",
            "download_headers": download_headers,
            "skip_intervals": list(self.skip_intervals),
            "advertisement": self.advertisement,
            "reload_data": self.reload_data,
            "duration": max(0, int(self.duration or 0)),
            "size": max(0, int(self.size or 0)),
            "transport": transport_from_url(self.url),
            "transport_metadata": dict(self.transport_metadata),
        }
        if self.video_track_index >= 0:
            result["video_track_index"] = int(self.video_track_index)
        if self.audio_track_index >= 0:
            result["audio_track_index"] = int(self.audio_track_index)
        if self.video_source.id:
            result["source_id"] = self.video_source.id
        if self.video_source.kinopoisk_id:
            result["provider_content_id"] = self.video_source.kinopoisk_id
        capabilities = source_capabilities(self.video_source.video_source_type_id)
        if capabilities["media_probe"]:
            result["transport_metadata"]["zona_media_probe"] = str(capabilities["media_probe"])
        if capabilities["web_loader"]:
            result["transport_metadata"]["zona_web_loader"] = "true"
        return result
