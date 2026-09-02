#!/usr/bin/env python3
import requests
import re
import urllib.parse
from typing import List, Dict, Any, Optional
from bs4 import BeautifulSoup
from zona_legacy_adapters import _resolve_hdrezka

HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36'
}

def _fetch_text(url, headers=None):
    try:
        r = requests.get(url, headers=headers or HEADERS, timeout=10)
        if r.status_code == 200:
            return r.text, None
        else:
            return None, f"HTTP_{r.status_code}"
    except Exception as e:
        return None, str(e)

def _fetch_post_form_text(url, headers=None, data=None):
    try:
        r = requests.post(url, headers=headers or HEADERS, data=data, timeout=10)
        if r.status_code == 200:
            return r.text, None
        else:
            return None, f"HTTP_{r.status_code}"
    except Exception as e:
        return None, str(e)

def search_rezka(title: str, year: Optional[int] = None) -> List[Dict[str, str]]:
    """Ищет фильм/сериал на rezka.ag, возвращает список результатов с url."""
    query = title
    if year:
        query += f" {year}"
    url = "https://rezka.ag/search/?do=search&subaction=search&q=" + urllib.parse.quote(query)
    try:
        resp = requests.get(url, headers=HEADERS, timeout=10)
        if resp.status_code != 200:
            return []
        soup = BeautifulSoup(resp.text, 'lxml')
        results = []
        # Находим блоки с результатами
        for item in soup.select('.b-content__inline_item'):
            link_tag = item.find('a', href=True)
            if not link_tag:
                continue
            href = link_tag['href']
            # Берём только ссылки на фильмы/сериалы/мультфильмы
            if any(part in href for part in ('/films/', '/series/', '/cartoons/')):
                title_text = link_tag.get_text(strip=True)
                if title_text and href:
                    results.append({
                        'title': title_text,
                        'url': href
                    })
        # Убираем дубликаты
        unique = []
        seen = set()
        for r in results:
            if r['url'] not in seen:
                seen.add(r['url'])
                unique.append(r)
        return unique[:5]
    except Exception as e:
        print(f"Rezka search error: {e}")
        return []

def resolve_rezka(
    title: str,
    year: int,
    category: str = "movies",
    season: Optional[int] = None,
    episode: Optional[int] = None,
) -> List[Dict[str, Any]]:
    """Получает потоки с Rezka через HDRezka-экстрактор."""
    clean_title = title.strip()
    results = search_rezka(clean_title, year)
    if not results:
        return []
    # Выбираем первый релевантный результат
    page_url = results[0]['url']
    # Извлекаем source_path из URL (путь без домена и .html)
    parsed = urllib.parse.urlparse(page_url)
    source_path = parsed.path.strip('/')
    if source_path.endswith('.html'):
        source_path = source_path[:-5]

    source = {'downloadLinkKey': source_path}

    streams, error = _resolve_hdrezka(
        source,
        fetch_text=_fetch_text,
        fetch_post_form_text=_fetch_post_form_text,
        request_user_agent=HEADERS['User-Agent'],
        season=season,
        episode=episode,
    )
    if not streams:
        if error:
            print(f"Rezka resolve error for {title}: {error}")
        return []

    # Добавляем поля, совместимые с Movia
    clean_streams = []
    for s in streams:
        url = str(s.get('url') or '').strip()
        if not url or any(url.lower().endswith(ext) for ext in ('.svg', '.png', '.jpg', '.jpeg', '.gif', '.ico', '.css', '.js', '.html')):
            continue
        s['source'] = 'Rezka.ag'
        s['provider'] = 'hdrezka'
        s['voice'] = s.get('voice') or 'HDrezka Studio'
        s['season'] = season
        s['episode'] = episode
        # Если URL является HLS manifest, помечаем как transport = hls
        if '.m3u8' in url:
            s['transport'] = 'hls'
        else:
            s['transport'] = 'direct'
        clean_streams.append(s)
    return clean_streams

if __name__ == '__main__':
    # Быстрый тест
    test_cases = [
        ("Интерстеллар", 2014, "movies"),
        ("Человек-паук", 2002, "movies"),
        ("Дюна", 2021, "movies"),
    ]
    for title, year, cat in test_cases:
        print(f"\n=== {title} ({year}) ===")
        streams = resolve_rezka(title, year, cat)
        print(f"Найдено потоков: {len(streams)}")
        for s in streams[:3]:
            print(f"  {s.get('voice')} | {s.get('quality')} | {s.get('url')[:80]}")
