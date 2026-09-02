#!/usr/bin/env python3
import requests
import re
from urllib.parse import quote_plus
from bs4 import BeautifulSoup

HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36'
}

def search_rezka(title, year=None):
    query = title
    if year:
        query += f" {year}"
    url = f"https://rezka.ag/search/?do=search&subaction=search&q={quote_plus(query)}"
    try:
        resp = requests.get(url, headers=HEADERS, timeout=10)
        if resp.status_code != 200:
            return []
        soup = BeautifulSoup(resp.text, 'lxml')
        items = soup.select('.b-content__inline_item-link')
        results = []
        for item in items[:5]:
            link = item.get('href')
            title_text = item.get_text(strip=True)
            if link and title_text:
                results.append({'title': title_text, 'url': link})
        return results
    except Exception as e:
        print(f"Rezka search error: {e}")
        return []

def get_rezka_magnets(page_url):
    try:
        resp = requests.get(page_url, headers=HEADERS, timeout=15)
        if resp.status_code != 200:
            return []
        soup = BeautifulSoup(resp.text, 'lxml')
        magnets = []
        # Ищем все ссылки с magnet:
        for a in soup.find_all('a', href=True):
            href = a['href']
            if 'magnet:' in href:
                # Пытаемся вытащить озвучку из ближайшего родителя
                parent = a.find_parent('li') or a.find_parent('div')
                voice = ''
                if parent:
                    # Ищем элементы с классом, содержащим 'info' или 'translation'
                    for class_name in ['b-post__info', 'info', 'translator', 'voice']:
                        tag = parent.find(class_=re.compile(class_name, re.I))
                        if tag:
                            voice = tag.get_text(strip=True)
                            break
                if not voice:
                    # Пытаемся найти текст рядом со ссылкой
                    text = a.get_text(strip=True)
                    if text:
                        voice = text
                magnets.append({
                    'url': href,
                    'voice': voice or 'HDrezka Studio',
                    'source': 'Rezka.ag'
                })
        return magnets
    except Exception as e:
        print(f"Rezka magnets error: {e}")
        return []

if __name__ == '__main__':
    # Быстрый тест
    results = search_rezka("Человек-паук Новый день", 2026)
    print("Найдено на Rezka:")
    for r in results:
        print(r['title'], r['url'])
        magnets = get_rezka_magnets(r['url'])
        for m in magnets:
            print("  magnet:", m['url'][:100], "voice:", m['voice'])
        break
