import os
import time
import requests
import urllib3
from typing import List, Dict, Optional, Any

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

class TMDbClient:
    def __init__(self, api_key: str = "6edd31b8201cbd29c437df73fcd3345d", language: str = "ru-RU"):
        self.api_key = os.getenv("TMDB_API_KEY", api_key)
        self.language = language
        self.base_url = "https://api.themoviedb.org/3"
        self.poster_base = "https://image.tmdb.org/t/p/w500"
        self.backdrop_base = "https://image.tmdb.org/t/p/original"
        self.person_base = "https://image.tmdb.org/t/p/w342"
        self.session = requests.Session()

    def _get(
        self,
        endpoint: str,
        params: Optional[Dict[str, Any]] = None,
        *,
        max_retries: int = 0,
    ) -> Optional[Dict[str, Any]]:
        url = f"{self.base_url}{endpoint}"
        default_params = {"api_key": self.api_key, "language": self.language}
        if params:
            default_params.update(params)
        # Retries are opt-in.  Ordinary detail/list callers retain the
        # historical single-request behavior; the bounded catalog worker can
        # request one retry for a failed discovery page.
        try:
            max_retries = max(0, min(1, int(max_retries)))
        except (TypeError, ValueError, OverflowError):
            max_retries = 0
        retryable_statuses = {429, 500, 502, 503, 504}
        max_retry_delay = 2.0
        for attempt in range(max_retries + 1):
            try:
                resp = self.session.get(url, params=default_params, timeout=10)
            except Exception:
                if attempt >= max_retries:
                    return None
                time.sleep(min(max_retry_delay, 0.5 * (2 ** attempt)))
                continue

            if resp.status_code == 200:
                try:
                    payload = resp.json()
                except (TypeError, ValueError):
                    return None
                return payload if isinstance(payload, dict) else None

            if resp.status_code not in retryable_statuses or attempt >= max_retries:
                return None

            retry_after = None
            try:
                retry_after = float((getattr(resp, "headers", {}) or {}).get("Retry-After"))
            except (TypeError, ValueError):
                retry_after = None
            if retry_after is not None:
                retry_after = max(0.0, retry_after)
                if retry_after > max_retry_delay:
                    return None
                delay = retry_after
            else:
                delay = min(max_retry_delay, 0.5 * (2 ** attempt))
            time.sleep(delay)
        return None

    def get_popular_movies(self, page: int = 1) -> List[Dict[str, Any]]:
        data = self._get("/movie/popular", {"page": page})
        return data.get("results", []) if data else []

    def get_popular_tv(self, page: int = 1) -> List[Dict[str, Any]]:
        data = self._get("/tv/popular", {"page": page})
        return data.get("results", []) if data else []

    def _parse_cast(self, credits: Dict[str, Any]) -> List[Dict[str, Any]]:
        result = []
        for person in (credits or {}).get("cast", [])[:12]:
            name = person.get("name")
            if not name:
                continue
            profile_path = person.get("profile_path")
            result.append({
                "name": name,
                "photo_url": f"{self.person_base}{profile_path}" if profile_path else None,
                "role": person.get("character") or None,
            })
        return result

    def get_movie_details(self, movie_id: int) -> Optional[Dict[str, Any]]:
        data = self._get(f"/movie/{movie_id}", {"append_to_response": "credits"})
        if not data:
            return None

        release_date = data.get("release_date", "")
        year = int(release_date.split("-")[0]) if release_date and len(release_date) >= 4 else 0
        if year and year < 1980:
            return None

        genres = [g["name"] for g in data.get("genres", [])]
        credits = data.get("credits", {})
        cast = self._parse_cast(credits)

        director = ""
        for crew in credits.get("crew", []):
            if crew.get("job") == "Director":
                director = crew.get("name", "")
                break

        poster_path = data.get("poster_path")
        backdrop_path = data.get("backdrop_path")
        poster_url = f"{self.poster_base}{poster_path}" if poster_path else None
        backdrop_url = f"{self.backdrop_base}{backdrop_path}" if backdrop_path else poster_url

        return {
            "tmdb_id": data.get("id"),
            "media_type": "movie",
            "title": data.get("title") or data.get("original_title") or "Без названия",
            "original_title": data.get("original_title"),
            "year": year,
            "rating": round(data.get("vote_average", 0.0), 1),
            "duration_minutes": data.get("runtime", 0),
            "synopsis": data.get("overview") or "Художественный фильм.",
            "poster_url": poster_url,
            "backdrop_url": backdrop_url,
            "genres": genres,
            "cast": cast,
            "director": director,
            "country": "Зарубежный",
            "category": "movies"
        }

    def get_tv_details(self, tv_id: int) -> Optional[Dict[str, Any]]:
        data = self._get(f"/tv/{tv_id}", {"append_to_response": "credits"})
        if not data:
            return None

        first_air = data.get("first_air_date", "")
        year = int(first_air.split("-")[0]) if first_air and len(first_air) >= 4 else 0
        if year and year < 1980:
            return None

        genres = [g["name"] for g in data.get("genres", [])]
        credits = data.get("credits", {})
        cast = self._parse_cast(credits)

        poster_path = data.get("poster_path")
        backdrop_path = data.get("backdrop_path")
        poster_url = f"{self.poster_base}{poster_path}" if poster_path else None
        backdrop_url = f"{self.backdrop_base}{backdrop_path}" if backdrop_path else poster_url
        season_rows = sorted(
            [s for s in data.get("seasons", []) if int(s.get("season_number") or 0) > 0],
            key=lambda item: int(item.get("season_number") or 0),
        )
        max_season = max([int(x.get("season_number") or 0) for x in season_rows], default=0)
        season_episode_counts = [0] * max_season
        for season in season_rows:
            number = int(season.get("season_number") or 0)
            if 1 <= number <= max_season:
                season_episode_counts[number - 1] = int(season.get("episode_count") or 0)

        return {
            "tmdb_id": data.get("id"),
            "media_type": "tv",
            "title": data.get("name") or data.get("original_name") or "Без названия",
            "original_title": data.get("original_name"),
            "year": year,
            "rating": round(data.get("vote_average", 0.0), 1),
            "duration_minutes": data.get("episode_run_time", [45])[0] if data.get("episode_run_time") else 45,
            "seasons_count": int(data.get("number_of_seasons") or max_season or 0),
            "episodes_count": int(data.get("number_of_episodes") or sum(season_episode_counts)),
            "season_episode_counts": season_episode_counts,
            "synopsis": data.get("overview") or "Сериал.",
            "poster_url": poster_url,
            "backdrop_url": backdrop_url,
            "genres": genres,
            "cast": cast,
            "director": "",
            "country": "Зарубежный",
            "category": "series"
        }

tmdb = TMDbClient()
