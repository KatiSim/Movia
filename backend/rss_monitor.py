import requests
import xml.etree.ElementTree as ET
import urllib3
from typing import List, Dict, Any
from database import save_content

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# Список открытых RSS-лент релизов
RSS_FEEDS = [
    "https://yts.mx/rss/0/all/all/0/en",
    "https://archive.org/services/collection-rss.php?collection=feature_films"
]

def parse_rss_feed(feed_url: str) -> List[Dict[str, Any]]:
    items = []
    try:
        resp = requests.get(feed_url, timeout=10, verify=False, headers={"User-Agent": "Mozilla/5.0"})
        if resp.status_code != 200:
            return []
        root = ET.fromstring(resp.content)
        for entry in root.findall(".//item"):
            title = entry.findtext("title", "").strip()
            enclosure = entry.find("enclosure")
            link = enclosure.get("url") if enclosure is not None else entry.findtext("link", "").strip()
            
            if not title or not link:
                continue
                
            items.append({
                "title": title,
                "playback_url": link,
                "source_id": "rss_feed",
                "source_page": feed_url,
                "media_type": "torrent_magnet" if link.startswith("magnet:") else "direct_http",
                "quality": "HD",
                "rating": 8.0,
                "synopsis": f"Новый релиз из ленты: {title}"
            })
    except Exception as e:
        print(f"[RSS] Ошибка парсинга {feed_url}: {e}")
    return items

def run_rss_sync():
    print("=== Запуск фонового мониторинга RSS-релизов ===")
    total_new = 0
    for feed in RSS_FEEDS:
        print(f"Опрос ленты: {feed}...", end=" ", flush=True)
        items = parse_rss_feed(feed)
        saved = sum(1 for item in items if save_content(item))
        total_new += saved
        print(f"новых записей: {saved}")
    print(f"RSS-мониторинг завершён. Добавлено: {total_new}")

if __name__ == "__main__":
    run_rss_sync()
