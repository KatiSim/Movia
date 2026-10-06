"""Movia-owned HDRezka HTTP and article decoding, independent of Zona config/runtime."""
from __future__ import annotations

import base64
import json
import logging
import re
import time
from dataclasses import dataclass
from concurrent.futures import ThreadPoolExecutor, wait
from threading import BoundedSemaphore
from html import unescape
from urllib.parse import quote, urlparse

from bs4 import BeautifulSoup
from hdrezka_episode_identity import selected_episode, exact_episode_page
from stream_validation import is_valid_stream_url, episode_coordinate

logger = logging.getLogger("hdrezka_transport")
_TRANSLATOR_WORKERS = ThreadPoolExecutor(max_workers=4, thread_name_prefix="movia-hd-translator")
_TRANSLATOR_SLOTS = BoundedSemaphore(16)


def _submit_translator(fn, translator):
    if not _TRANSLATOR_SLOTS.acquire(blocking=False):
        return None
    slots = _TRANSLATOR_SLOTS
    try:
        future = _TRANSLATOR_WORKERS.submit(fn, translator)
    except Exception:
        slots.release()
        raise
    future.add_done_callback(lambda _: slots.release())
    return future

HDREZKA_DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
)
# Public provider route already used by Movia, with its canonical virtual host.
# No remote app configuration, account credentials or authentication headers.
ARTICLE_ROUTES = (("http://hdrzk.org", "rezka.ag"), ("https://hdrezka.ag", None))
_NOISE = ("$$#!!@#!@##", "^^^!@##!!##", "####^!!##!@@", "@@@@@!##!^^^", "$$!!@$$@^!@#$$@")
_INIT = re.compile(r"initCDN(?:Movies|Series)Events\s*\(\s*(\d+)\s*,\s*(\d+)\s*,", re.I)


@dataclass(frozen=True)
class Translator:
    article_id: str
    translator_id: str
    voice: str
    active: bool
    playlist: str = ""
    camrip: str = "0"
    ads: str = "0"
    director: str = "0"


def _path(source):
    value = source.get("downloadLinkKey") or source.get("article_ref") or ""
    value = str(value).strip()
    if value.startswith(("http://", "https://")):
        value = urlparse(value).path
    value = value.strip("/").removesuffix(".html")
    if not value or len(value) > 768 or any(c in value for c in "\r\n?#\\"):
        return None
    if any(part in (".", "..", "") for part in value.split("/")):
        return None
    return value


def decode_playlist(value):
    text = unescape(str(value or "").strip()).replace("\\/", "/")
    if not text.startswith("#"):
        return text
    if not text.startswith(("#h", "#2")):
        return None
    encoded = text[2:]
    if text.startswith("#h"):
        # The observed provider envelope contains fixed-size separator records.
        while "//_//" in encoded:
            at = encoded.index("//_//")
            if at + 21 > len(encoded):
                return None
            encoded = encoded[:at] + encoded[at + 21:]
    else:
        for marker in _NOISE:
            encoded = encoded.replace("//_//" + base64.b64encode(marker.encode()).decode(), "")
    try:
        return base64.b64decode(encoded + "=" * (-len(encoded) % 4), validate=True).decode("utf-8")
    except (ValueError, UnicodeError):
        return None


def playlist_leaves(value):
    """Every actual alternative is retained. Labels remain advertised metadata."""
    result = []
    def add(url, label=""):
        url = unescape(str(url or "").strip()).replace("\\/", "/")
        if url.startswith("//"):
            url = "https:" + url
        if is_valid_stream_url(url):
            pair = (url, str(label or "").strip())
            if pair not in result:
                result.append(pair)
    if isinstance(value, dict):
        for label, entry in value.items():
            add(entry.get("url") or entry.get("file") or entry.get("src"), label) if isinstance(entry, dict) else add(entry, label)
        return result
    if isinstance(value, list):
        for entry in value:
            if isinstance(entry, dict):
                add(entry.get("url") or entry.get("file") or entry.get("src"), entry.get("quality") or entry.get("label"))
            else:
                for url, label in playlist_leaves(entry):
                    add(url, label)
        return result
    decoded = decode_playlist(value)
    if decoded is None:
        return []
    groups = list(re.finditer(r"\[([^\]]+)\]([^\[]*)", decoded))
    for group in groups:
        for match in re.finditer(r"(?:https?:)?//[^,\s]+", group[2], re.I):
            add(match[0].rstrip("'\";)"), group[1])
    if not groups:
        for match in re.finditer(r"(?:https?:)?//[^,\s]+", decoded, re.I):
            add(match[0].rstrip("'\";)"))
    return result


def embedded_payload(page):
    decoder = json.JSONDecoder()
    for match in re.finditer(r'\{\s*"id"\s*:\s*"cdnplayer"', page):
        try:
            payload, _ = decoder.raw_decode(page[match.start():])
            if isinstance(payload, dict):
                return payload
        except ValueError:
            pass
    return None


def translators(page, article_id):
    soup = BeautifulSoup(page, "lxml")
    init = _INIT.search(page)
    selected_id = init[2] if init and init[1] == article_id else ""
    result = {}
    for tag in soup.select("[data-translator_id]"):
        tid = str(tag.get("data-translator_id") or "")
        item_id = str(tag.get("data-id") or article_id)
        if not tid.isascii() or not tid.isdecimal() or item_id != article_id:
            continue
        voice = str(tag.get("title") or tag.get_text(" ", strip=True) or "Не указано").strip()
        result[tid] = Translator(
            article_id, tid, voice or "Не указано",
            "active" in tag.get("class", []) or tid == selected_id,
            str(tag.get("data-cdn_url") or ""),
            str(tag.get("data-camrip") or "0"), str(tag.get("data-ads") or "0"),
            str(tag.get("data-director") or "0"),
        )
    if not result and selected_id:
        result[selected_id] = Translator(article_id, selected_id, "Не указано", True)
    return tuple(result.values())


def cookie_header(headers):
    values = [v for k, v in (headers or {}).items() if str(k).lower() == "set-cookie"]
    pairs = []
    for value in values:
        for raw in value if isinstance(value, (list, tuple)) else [value]:
            pair = str(raw).split(";", 1)[0].strip()
            if "=" not in pair or "\r" in pair or "\n" in pair or len(pair) > 2048:
                continue
            name, val = pair.split("=", 1)
            if re.fullmatch(r"[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}", name) and val and val.casefold() != "deleted":
                pairs.append(pair)
    return "; ".join(dict.fromkeys(pairs))


def _rows(payload, translator, page_url, user_agent, season, episode, evidence):
    if not isinstance(payload, dict) or payload.get("success") is False:
        return []
    subtitles = []
    raw_subtitles = payload.get("subtitle")
    if isinstance(raw_subtitles, str):
        for part in raw_subtitles.split(","):
            match = re.fullmatch(r"\[([^\]]+)\](.+)", part.strip())
            if match and is_valid_stream_url(match[2]):
                labels = payload.get("subtitle_lns")
                labels = labels if isinstance(labels, dict) else {}
                subtitles.append({"url": match[2], "language": str(labels.get(match[1]) or match[1])})
    result = []
    for url, label in playlist_leaves(payload.get("url") or payload.get("streams")):
        metadata = {"hdrezka_native_transport": True, "advertised_quality": label}
        if season and episode:
            metadata.update(hdrezka_episode_verified=True, hdrezka_episode_evidence=evidence)
        row = {
            "source": "HDRezka", "provider": "HDRezka", "url": url,
            "voice": translator.voice, "translation": translator.voice,
            # Provider labels are not dimensions. The content probe fills quality.
            "quality": "Не указано", "advertised_quality": label,
            "user_agent": user_agent, "headers": {
                "User-Agent": user_agent, "Referer": page_url,
                "Origin": urlparse(page_url).scheme + "://" + urlparse(page_url).netloc,
            },
            "transport": "hls" if urlparse(url).path.lower().endswith(".m3u8") else "direct",
            "transport_metadata": metadata,
            "subtitles": subtitles,
        }
        if season and episode:
            row.update(season=season, episode=episode)
        result.append(row)
    return result


def resolve_hdrezka(source, *, fetch_text, fetch_post_form_text,
                    fetch_text_with_headers=None, request_user_agent=HDREZKA_DEFAULT_USER_AGENT,
                    season=None, episode=None):
    path = _path(source)
    match = re.match(r"(\d+)(?:-|$)", (path or "").rsplit("/", 1)[-1])
    if not path or not match:
        return [], "SOURCE_REF_INCOMPLETE"
    article_id = match[1]
    is_series = season is not None or episode is not None
    if is_series:
        season, episode = episode_coordinate(season), episode_coordinate(episode)
        if season is None or episode is None:
            return [], "HDREZKA_EPISODE_COORDINATES_INVALID"
    if fetch_text is None:
        return [], "ADAPTER_REQUEST_UNAVAILABLE"
    ua = request_user_agent or HDREZKA_DEFAULT_USER_AGENT
    page = page_url = None
    headers = {}
    cookies = ""
    last_error = "HDREZKA_PAGE_UNAVAILABLE"
    for origin, host in ARTICLE_ROUTES:
        url = origin + "/" + quote(path, safe="/-._~") + ".html"
        request_headers = {"User-Agent": ua, "Accept-Encoding": "gzip"}
        if host:
            request_headers["Host"] = host
        try:
            if fetch_text_with_headers:
                text, response_headers, error = fetch_text_with_headers(url, request_headers)
            else:
                text, error = fetch_text(url, request_headers)
                response_headers = {}
        except Exception as exc:
            text, error, response_headers = None, type(exc).__name__, {}
        if error or not isinstance(text, str) or not text.strip():
            last_error = str(error or last_error)
            continue
        soup = BeautifulSoup(text, "lxml")
        if soup.select_one(".b-player__restricted__block_message"):
            return [], "HDREZKA_RESTRICTED"
        if soup.title and any(t in soup.title.get_text().casefold() for t in ("sign in", "не бот", "captcha")):
            last_error = "HDREZKA_ACCESS_CHALLENGE"
            continue
        init = _INIT.search(text)
        if init and init[1] != article_id:
            return [], "HDREZKA_ARTICLE_ID_MISMATCH"
        if not init and embedded_payload(text) is None:
            last_error = "HDREZKA_PLAYER_MISSING"
            continue
        page, page_url, headers, cookies = text, url, request_headers, cookie_header(response_headers)
        break
    if page is None:
        return [], last_error
    if is_series and selected_episode(page, article_id) != (season, episode):
        url = exact_episode_page(page, page_url, season, episode)
        if not url:
            return [], "HDREZKA_EPISODE_UNVERIFIED"
        try:
            if fetch_text_with_headers:
                text, response_headers, error = fetch_text_with_headers(url, {**headers, "Referer": page_url})
            else:
                text, error = fetch_text(url, {**headers, "Referer": page_url})
                response_headers = {}
        except Exception as exc:
            return [], type(exc).__name__
        if error or selected_episode(text or "", article_id) != (season, episode):
            return [], "HDREZKA_EPISODE_UNVERIFIED"
        page, page_url = text, url
        cookies = cookie_header(response_headers) or cookies
    branches = translators(page, article_id)
    active = [t for t in branches if t.active]
    chosen = active[0] if len(active) == 1 else Translator(article_id, "", "Не указано", True)
    rows = _rows(embedded_payload(page), chosen, page_url, ua, season, episode, "embedded-page")
    pending_branches = []
    for translator in branches:
        if translator.playlist and (not is_series or translator.active):
            rows.extend(_rows({"url": translator.playlist}, translator, page_url, ua, season, episode, "translator-page"))
        else:
            pending_branches.append(translator)

    def request_branch(translator):
        form = {"id": article_id, "translator_id": translator.translator_id,
                "action": "get_stream" if is_series else "get_movie"}
        if is_series:
            form.update(season=str(season), episode=str(episode))
        else:
            form.update(is_camrip=translator.camrip, is_ads=translator.ads, is_director=translator.director)
        post_headers = {**headers, "Referer": page_url, "Origin": urlparse(page_url).scheme + "://" + urlparse(page_url).netloc}
        if cookies:
            post_headers["Cookie"] = cookies
        try:
            body, error = fetch_post_form_text(
                urlparse(page_url).scheme + "://" + urlparse(page_url).netloc + "/ajax/get_cdn_series/?t=" + str(int(time.time() * 1000)),
                post_headers, form,
            )
        except Exception as exc:
            body, error = None, type(exc).__name__
        if error or not body:
            return [], error
        try:
            return _rows(json.loads(body), translator, page_url, ua, season, episode, "episode-ajax"), None
        except (ValueError, TypeError):
            return [], "INVALID_RESPONSE"

    if fetch_post_form_text is not None and pending_branches:
        # One request establishes whether this route is available. A hard 403
        # never triggers a fan-out, while published branches are already kept.
        first_rows, first_error = request_branch(pending_branches[0])
        rows.extend(first_rows)
        if not str(first_error or "").startswith("HTTP_ERROR:403"):
            futures = []
            for translator in pending_branches[1:]:
                future = _submit_translator(request_branch, translator)
                if future is not None:
                    futures.append(future)
            done, pending = wait(futures, timeout=2.0) if futures else (set(), set())
            for future in pending:
                future.cancel()
            # Preserve article ordering rather than completion timing.
            for future in futures:
                if future in done:
                    try:
                        branch_rows, _ = future.result()
                        rows.extend(branch_rows)
                    except Exception:
                        pass
    unique = {}
    for row in rows:
        key = (row["url"], row["voice"], row.get("season"), row.get("episode"))
        unique[key] = row
    logger.info("HDRezka native article=%s series=%s branches=%s leaves=%s",
                article_id, is_series, len(branches), len(unique))
    return list(unique.values()), None if unique else "HDREZKA_NO_PLAYABLE_URL"
