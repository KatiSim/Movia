#!/usr/bin/env python3
"""Filmix clean-room adapter on top of the generic provider VariantTree contract.

The structure follows the verified LazyMedia Deluxe Filmix architecture:
article identity -> translation folder -> season/episode folders -> concrete
quality/file leaves. Network/parsing primitives are the already verified
Filmix contract helpers; this module owns only clean-room orchestration and
VariantTree construction.
"""
from __future__ import annotations

import re
import time
from html import unescape
from typing import Any, Callable, Dict, Optional, Tuple

from provider_contract import (
    ProviderArticle,
    ProviderDefinition,
    ProviderRequest,
    ProviderRequestProfile,
    VariantFolder,
    VariantStream,
)
from zona_legacy_adapters import (
    FILMIX_DEFAULT_USER_AGENT,
    FILMIX_PLAYER_PATH,
    _filmix_base_candidates,
    _filmix_direct_urls,
    _filmix_player_payload,
    _filmix_post_id,
)


TextFetcher = Callable[[str, Dict[str, str]], Tuple[Optional[str], Optional[str]]]
PostFormFetcher = Callable[
    [str, Dict[str, str], Dict[str, str]],
    Tuple[Optional[str], Optional[str]],
]


FILMIX_PROVIDER = ProviderDefinition(
    provider_id="lazy:filmix",
    name="Filmix",
    family="lazy",
    enabled=True,
    source_type_id=3,
    request_profile=ProviderRequestProfile(
        user_agent=FILMIX_DEFAULT_USER_AGENT,
        base_urls=("https://filmix.ac", "http://filmixapp.cyou"),
        properties={"architecture": "lazy-3.466"},
    ),
    capabilities=frozenset({"movie", "series", "voice", "quality", "mirror-fallback"}),
)


def _clean_voice(value: Any) -> str:
    return re.sub(r"\s+", " ", unescape(str(value or "")).strip()) or "Не указано"


class FilmixProviderAdapter:
    definition = FILMIX_PROVIDER

    def resolve_source(
        self,
        source: Dict[str, Any],
        request: ProviderRequest,
        *,
        fetch_text: Optional[TextFetcher],
        fetch_post_form_text: Optional[PostFormFetcher],
        request_user_agent: str = "",
    ) -> tuple[Optional[VariantFolder], Optional[ProviderArticle], Optional[str]]:
        post_id = _filmix_post_id(source)
        if not post_id:
            return None, None, "SOURCE_REF_INCOMPLETE"
        if fetch_text is None or fetch_post_form_text is None:
            return None, None, "ADAPTER_REQUEST_UNAVAILABLE"

        user_agent = request_user_agent or FILMIX_DEFAULT_USER_AGENT
        page_text = ""
        page_url = ""
        base_url = ""
        page_errors: list[str] = []
        for base in _filmix_base_candidates(source):
            candidate = f"{base}/play/{post_id}"
            headers = {
                "User-Agent": user_agent,
                "Accept": "text/html,*/*;q=0.8",
            }
            try:
                text, error = fetch_text(candidate, headers)
            except Exception as exc:
                page_errors.append(type(exc).__name__)
                continue
            if error or not isinstance(text, str) or not text.strip():
                page_errors.append(str(error or "EMPTY_PAGE")[:120])
                continue
            page_text = text
            page_url = candidate
            base_url = base
            break

        if not page_text:
            suffix = ":" + ";".join(page_errors[:3]) if page_errors else ""
            return None, None, "filmix:PAGE_REQUEST_FAILED" + suffix

        request_headers = {
            "User-Agent": user_agent,
            "Referer": page_url,
            "Origin": base_url,
            "Accept": "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With": "XMLHttpRequest",
            "Cache-Control": "no-cache",
            "Pragma": "no-cache",
        }
        endpoint = f"{base_url}{FILMIX_PLAYER_PATH}{int(time.time() * 1000)}"
        form = {"post_id": post_id, "showfull": "true"}
        try:
            response, error = fetch_post_form_text(endpoint, request_headers, form)
        except Exception as exc:
            return None, None, f"filmix:{type(exc).__name__}:{str(exc)[:120]}"
        if error:
            return None, None, f"filmix:{error}"
        if not isinstance(response, str) or not response.strip():
            return None, None, "filmix:EMPTY_RESPONSE"

        video_map, parse_error = _filmix_player_payload(response)
        if parse_error:
            return None, None, f"filmix:{parse_error}"

        article = ProviderArticle(
            provider=self.definition,
            item_id=str(post_id),
            title=request.title,
            year=request.year,
            article_ref=page_url,
            content_ref=str(post_id),
        )

        playback_headers = {
            "User-Agent": user_agent,
            "Referer": page_url,
            "Origin": base_url,
        }

        voice_nodes: list[VariantFolder] = []
        opaque_entries = 0
        for raw_voice, raw_value in video_map.items():
            voice = _clean_voice(raw_voice)
            candidates = _filmix_direct_urls(raw_value)
            if not candidates and raw_value not in (None, "", [], {}):
                opaque_entries += 1
                continue

            leaves: list[VariantStream] = []
            per_dimension_counts: dict[tuple[str, str], int] = {}
            for stream_url, quality in candidates:
                if stream_url.lower().split("?", 1)[0].endswith(".txt"):
                    opaque_entries += 1
                    continue
                dimension = (voice, quality)
                ordinal = per_dimension_counts.get(dimension, 0)
                per_dimension_counts[dimension] = ordinal + 1
                stream_key = f"voice:{voice}|quality:{quality}|index:{ordinal}"
                leaves.append(
                    VariantStream(
                        url=stream_url,
                        stream_key=stream_key,
                        voice=voice,
                        quality=quality,
                        season=request.season,
                        episode=request.episode,
                        headers=playback_headers,
                        user_agent=user_agent,
                    )
                )

            if leaves:
                voice_nodes.append(
                    VariantFolder(
                        voice=voice,
                        season=request.season,
                        episode=request.episode,
                        children=tuple(leaves),
                    )
                )

        if not voice_nodes:
            if opaque_entries:
                return None, None, "filmix:OPAQUE_LINK_DECODER_REQUIRED"
            return None, None, "filmix:NO_PLAYABLE_URL"

        if request.is_series_request:
            episode_folder = VariantFolder(
                season=request.season,
                episode=request.episode,
                children=tuple(voice_nodes),
            )
            season_folder = VariantFolder(
                season=request.season,
                children=(episode_folder,),
            )
            root = VariantFolder(children=(season_folder,))
        else:
            root = VariantFolder(children=tuple(voice_nodes))

        return root, article, None
