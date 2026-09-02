import re
import requests
import urllib3
import xml.etree.ElementTree as ET
from typing import List, Dict, Optional, Any
from urllib.parse import quote, unquote
from archive_source import search_archive_streams_sync

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

class VideoSearchEngine:
    """Динамический поисковый движок по децентрализованным и открытым медиа-индексам."""

    def __init__(self, timeout: int = 8):
        self.timeout = timeout
        self.session = requests.Session()
        self.session.verify = False
        self.session.headers.update({
            "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept": "application/json, application/xml, text/xml, */*",
        })
        
        # Конфигурация локального/удалённого Jackett/Prowlarr (если запущен)
        self.jackett_host = "http://127.0.0.1:9117"
        self.jackett_api_key = ""  # Указать при наличии

    def search(self, query: str, year: Optional[int] = None) -> List[Dict[str, Any]]:
        results: List[Dict[str, Any]] = []
        
        # 1. Запрос к открытым REST API
        results.extend(self._search_yts(query, year))
        results.extend(self._search_apibay(query, year))
        results.extend(self._search_archive_org(query, year))
        
        # 2. Запрос к шлюзу Torznab (Jackett), если задан API-ключ
        if self.jackett_api_key:
            results.extend(self._search_torznab(query))
            
        return results

    def _extract_year(self, text: str) -> Optional[int]:
        match = re.search(r'\b(19\d\d|20\d\d)\b', text)
        return int(match.group(1)) if match else None

    def _search_yts(self, query: str, year: Optional[int] = None) -> List[Dict[str, Any]]:
        mirrors = ["https://yts.mx/api/v2/list_movies.json", "https://yts.rs/api/v2/list_movies.json"]
        for base_url in mirrors:
            try:
                resp = self.session.get(base_url, params={"query_term": query, "limit": 4}, timeout=self.timeout)
                if resp.status_code == 200 and "application/json" in resp.headers.get("Content-Type", ""):
                    data = resp.json()
                    movies = data.get("data", {}).get("movies", []) or []
                    items = []
                    for movie in movies:
                        m_title = movie.get("title_long") or movie.get("title")
                        m_year = movie.get("year")
                        for torrent in movie.get("torrents", [])[:1]:
                            h = torrent.get("hash")
                            q = torrent.get("quality", "HD")
                            if not h:
                                continue
                            full_name = f"{m_title} [{q}]"
                            magnet = f"magnet:?xt=urn:btih:{h}&dn={quote(full_name)}"
                            items.append({
                                "title": full_name,
                                "original_title": query,
                                "year": m_year or self._extract_year(m_title),
                                "playback_url": magnet,
                                "source_id": "yts_api",
                                "source_page": movie.get("url"),
                                "media_type": "torrent_magnet"
                            })
                    return items
            except Exception:
                continue
        return []

    def _search_apibay(self, query: str, year: Optional[int] = None) -> List[Dict[str, Any]]:
        url = "https://apibay.org/q.php"
        q = f"{query} {year}".strip() if year else query
        try:
            resp = self.session.get(url, params={"q": q, "cat": "200"}, timeout=self.timeout)
            if resp.status_code != 200 or not resp.text.strip():
                return []
            data = resp.json()
            if not isinstance(data, list) or (len(data) == 1 and data[0].get("name") == "No results returned"):
                return []
            
            items = []
            for item in data[:3]:
                name, info_hash = item.get("name"), item.get("info_hash")
                if not name or not info_hash or info_hash == "0" * 40:
                    continue
                items.append({
                    "title": name,
                    "original_title": query,
                    "year": self._extract_year(name) or year,
                    "playback_url": f"magnet:?xt=urn:btih:{info_hash}&dn={quote(name)}",
                    "source_id": "apibay_p2p",
                    "source_page": f"https://apibay.org/q.php?q={quote(query)}",
                    "media_type": "torrent_magnet"
                })
            return items
        except Exception:
            return []

    def _search_archive_org(self, query: str, year: Optional[int] = None) -> List[Dict[str, Any]]:
        """Return only verified Archive media files, never a guessed filename."""
        try:
            verified = search_archive_streams_sync(
                title=query,
                year=year,
                timeout=min(max(float(self.timeout), 1.0), 5.0),
            )
        except Exception:
            return []

        items: List[Dict[str, Any]] = []
        for stream in verified:
            playback_url = str(stream.get("playback_url") or "").strip()
            if not playback_url:
                continue
            items.append({
                "title": stream.get("title") or query,
                "original_title": query,
                "year": stream.get("year") or year,
                "playback_url": playback_url,
                "source_id": "archive_org",
                "provider_item_id": stream.get("provider_item_id"),
                "source_page": stream.get("source_page"),
                "media_type": stream.get("media_type") or "direct_http",
                "voice": stream.get("voice") or "Original",
                "quality": stream.get("quality") or "Не указано",
            })
        return items

    def _search_torznab(self, query: str) -> List[Dict[str, Any]]:
        """Запрос к агрегаторам Jackett / Prowlarr по стандарту Torznab."""
        url = f"{self.jackett_host}/api/v2.0/indexers/all/results/torznab/api"
        params = {"apikey": self.jackett_api_key, "t": "search", "q": query, "cat": "2000"}
        try:
            resp = self.session.get(url, params=params, timeout=self.timeout)
            root = ET.fromstring(resp.content)
            items = []
            for item in root.findall(".//item")[:5]:
                title = item.findtext("title", "")
                enclosure = item.find("enclosure")
                link = enclosure.get("url") if enclosure is not None else item.findtext("link", "")
                if not link:
                    continue
                items.append({
                    "title": title,
                    "original_title": query,
                    "year": self._extract_year(title),
                    "playback_url": link,
                    "source_id": "torznab_indexer",
                    "source_page": item.findtext("comments", ""),
                    "media_type": "torrent_magnet" if link.startswith("magnet:") else "torrent_file"
                })
            return items
        except Exception:
            return []
