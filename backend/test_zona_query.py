import json, requests

def test_query():
    with open('/data/data/com.termux/files/home/projects/media-parser/config/zona_api.json') as f:
        cfg = json.load(f)

    for mirror in cfg.get("mirrors", []):
        url = f"{mirror}/api/search"
        try:
            r = requests.get(url, params={"query": "Человек-паук", "year": 2021}, headers=cfg.get("headers"), timeout=3.5, verify=False)
            if r.status_code == 200:
                print(f"[OK] Зеркало {mirror} доступно. Ответ получен.")
                return
        except Exception as e:
            print(f"[FAIL] Зеркало {mirror}: {e}")

if __name__ == "__main__":
    test_query()
