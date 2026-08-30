import urllib.request
import json
import ssl

TMDB_API_KEY = "b997cbe6072fa6ec0c5418b628db9454"
test_url = f"https://api.themoviedb.org/3/movie/popular?api_key={TMDB_API_KEY}&language=ru-RU&page=1"

print(f"Тестирование запроса к: {test_url[:60]}...")

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

req = urllib.request.Request(
    test_url,
    headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"}
)

try:
    with urllib.request.urlopen(req, timeout=10, context=ctx) as resp:
        print(f"HTTP Status: {resp.status}")
        data = json.loads(resp.read().decode("utf-8"))
        results = data.get("results", [])
        print(f"Получено фильмов: {len(results)}")
        if results:
            print(f"Пример первого фильма: {results[0].get('title')} ({results[0].get('id')})")
except Exception as e:
    print(f"❌ ОШИБКА подключения: {type(e).__name__} -> {e}")
