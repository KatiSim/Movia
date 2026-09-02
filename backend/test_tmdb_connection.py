import os

from tmdb_client import TMDbClient


client = TMDbClient()
if not client.api_key and not client.access_token:
    print("TMDb credentials are not configured in the environment")
else:
    data = client._get("/movie/popular", {"page": 1})
    results = data.get("results", []) if isinstance(data, dict) else []
    print(f"TMDb response: {'OK' if data is not None else 'ERROR'}; items={len(results)}")
