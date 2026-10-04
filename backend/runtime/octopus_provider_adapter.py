#!/usr/bin/env python3
"""Clean-room Octopus discovery adapter derived from LazyMedia Deluxe 3.466.

Search/article transport is live as of 2026-10-04. Playback remains fail-closed:
the legacy HDVB decoder depends on an old resolver contract that has not yet
been reproduced without copying static provider secrets.
"""
from __future__ import annotations

import html
import re
from dataclasses import dataclass
from typing import Callable, Dict, Optional, Tuple
from urllib.parse import quote, urljoin, urlsplit

from catalog_schema_v2 import normalize_ru_text
from provider_contract import ProviderDefinition, ProviderRequestProfile, ProviderSearchResult


TextFetcher = Callable[[str, Dict[str, str]], Tuple[Optional[str], Optional[str]]]

OCTOPUS_PROVIDER = ProviderDefinition(
    provider_id="lazy:octopus",
    name="Octopus",
    family="lazy",
    enabled=True,
    request_profile=ProviderRequestProfile(
        headers={"Referer": "https://yandex.ru/"},
        base_urls=(
            "https://007.ultradox.lol",
            "https://001.ultradox.vip",
            "https://ultadox.space",
        ),
        properties={"architecture": "lazy-3.466"},
    ),
    capabilities=frozenset({"movie", "search", "article", "web-embed"}),
)

_SEARCH_TEMPLATE = "/index.php?do=search&subaction=search&from_page=0&story={query}"


@dataclass(frozen=True)
class OctopusArticleRef:
    item_id: str
    title: str
    year: Optional[int]
    article_url: str
    iframe_url: str


def _strip_tags(value: str) -> str:
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", value))).strip()


def _year(value: str) -> Optional[int]:
    matches = re.findall(r"(?<!\d)(19\d{2}|20\d{2}|21\d{2})(?!\d)", value)
    return int(matches[-1]) if matches else None


def _search_cards(page: str, base_url: str) -> list[ProviderSearchResult]:
    rows: list[ProviderSearchResult] = []
    # Keep the parser structurally tied to the exact LazyMedia card marker.
    for match in re.finditer(
        r'<div[^>]+class=["\'][^"\']*top__slider_div__item[^"\']*["\'][^>]*>(.*?)</div>',
        page,
        re.S | re.I,
    ):
        block = match.group(1)
        href = re.search(r'<a[^>]+href=["\']([^"\']+)["\']', block, re.I)
        if not href:
            continue
        text = _strip_tags(block)
        if not text:
            continue
        # LazyMedia derives title from the span text and strips the year marker.
        title = re.sub(r"\s*\((?:19|20|21)\d{2}\).*?$", "", text).strip()
        if not title:
            title = text
        article_url = urljoin(base_url + "/", html.unescape(href.group(1)))
        path = urlsplit(article_url).path.rstrip("/")
        item_id = path.rsplit("/", 1)[-1] or path
        rows.append(
            ProviderSearchResult(
                provider=OCTOPUS_PROVIDER,
                item_id=item_id,
                title=title,
                year=_year(text),
                article_ref=article_url,
                content_ref=article_url,
            )
        )
    return rows


class OctopusProviderAdapter:
    definition = OCTOPUS_PROVIDER

    def search(
        self,
        title: str,
        *,
        fetch_text: TextFetcher,
    ) -> tuple[list[ProviderSearchResult], Optional[str]]:
        query = str(title or "").strip()
        if not query:
            return [], "SEARCH_QUERY_REQUIRED"
        errors: list[str] = []
        for base in self.definition.request_profile.base_urls:
            url = base.rstrip("/") + _SEARCH_TEMPLATE.format(query=quote(query, safe=""))
            body, error = fetch_text(url, {"Referer": "https://yandex.ru/", "Accept": "text/html,*/*;q=0.8"})
            if error or not body:
                errors.append(str(error or "EMPTY_RESPONSE"))
                continue
            # Use the final host only through absolute hrefs/urljoin; no guessed title URLs.
            rows = _search_cards(body, base)
            if rows:
                return rows, None
        return [], "OCTOPUS_SEARCH_FAILED" + (":" + ";".join(errors[:3]) if errors else "")

    def resolve_article(
        self,
        result: ProviderSearchResult,
        *,
        fetch_text: TextFetcher,
    ) -> tuple[Optional[OctopusArticleRef], Optional[str]]:
        body, error = fetch_text(
            result.article_ref,
            {"Referer": result.article_ref, "Accept": "text/html,*/*;q=0.8"},
        )
        if error or not body:
            return None, "OCTOPUS_ARTICLE_FAILED"
        iframe = re.search(
            r'<div[^>]+class=["\'][^"\']*full-story_iframe-block[^"\']*["\'][^>]*>.*?'
            r'<iframe[^>]+src=["\']([^"\']+)["\']',
            body,
            re.S | re.I,
        )
        if not iframe:
            return None, "OCTOPUS_IFRAME_MISSING"
        iframe_url = urljoin(result.article_ref, html.unescape(iframe.group(1)))
        if urlsplit(iframe_url).scheme not in {"http", "https"}:
            return None, "OCTOPUS_IFRAME_INVALID"
        return OctopusArticleRef(
            item_id=result.item_id,
            title=result.title,
            year=result.year,
            article_url=result.article_ref,
            iframe_url=iframe_url,
        ), None

    @staticmethod
    def exact_match(
        results: list[ProviderSearchResult],
        *,
        title: str,
        original_title: Optional[str],
        year: int,
    ) -> tuple[Optional[ProviderSearchResult], str]:
        expected = {
            normalize_ru_text(v)
            for v in (title, original_title)
            if normalize_ru_text(v)
        }
        exact = [
            row for row in results
            if normalize_ru_text(row.title) in expected
            and (not year or row.year is None or row.year == year)
        ]
        unique = {row.item_id: row for row in exact}
        if len(unique) == 1:
            return next(iter(unique.values())), "OK"
        if len(unique) > 1:
            return None, "AMBIGUOUS"
        return None, "NO_MATCH"
