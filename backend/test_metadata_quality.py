import unittest

from metadata_quality import bayesian_rating
from catalog_api import (
    _balanced_catalog_items,
    _catalog_region_bucket,
    _normalize_person_credit_scope,
    _remote_credit_matches_scope,
    _row_has_exact_person_credit,
    map_row_to_media,
)


class MetadataQualityTest(unittest.TestCase):
    def test_bayesian_rating_damps_tiny_vote_sample(self):
        self.assertLess(bayesian_rating(10.0, 4), 7.0)
        self.assertGreater(bayesian_rating(8.5, 10000), 8.4)
        self.assertEqual(bayesian_rating(0.0, 100), 0.0)

    def test_tv_creator_replaces_legacy_director_for_display(self):
        row = {
            "id": 357,
            "tmdb_id": 40075,
            "media_type": "tv",
            "title": "Гравити Фолз",
            "localized_ru_title": "Гравити Фолз",
            "original_title": "Gravity Falls",
            "alternative_titles": "[]",
            "year": 2012,
            "rating": 8.6,
            "vote_count": 3582,
            "vote_average": 8.622,
            "duration_minutes": 23,
            "seasons_count": 2,
            "episodes_count": 40,
            "season_episode_counts": "[20,20]",
            "collection_id": 0,
            "poster_url": "https://example.invalid/poster.jpg",
            "backdrop_url": "https://example.invalid/backdrop.jpg",
            "genres": '["мультфильм","комедия","семейный","детектив"]',
            "cast": "[]",
            "director": "Joe D'Amato",
            "creators": '["Алекс Хирш"]',
            "country": "США",
            "category": "tv_series",
            "streams": "[]",
            "quality": "1080p",
            "seeders": 0,
            "link_verified": 0,
            "link_updated_at": None,
            "imdb_id": "tt1865718",
            "metadata_source": "tmdb_detail",
        }
        media = map_row_to_media(row, compact=False)
        self.assertEqual(media["director"], "Алекс Хирш")
        self.assertEqual(media["creators"], ["Алекс Хирш"])
        self.assertNotIn("Joe D'Amato", media["director"])

    def test_neutral_catalog_interleaves_regional_blocks(self):
        items = []
        countries = (["США"] * 20) + (["Великобритания"] * 10) + (["Япония"] * 5) + (["Новая Зеландия"] * 5)
        for i, country in enumerate(countries):
            items.append({
                "id": str(i),
                "country": country,
                "mediaType": "movie",
                "category": "MOVIES",
            })
        result = _balanced_catalog_items(items, 40)
        streak = max_streak = 0
        previous = None
        for item in result:
            bucket = _catalog_region_bucket(item)
            streak = streak + 1 if bucket == previous else 1
            max_streak = max(max_streak, streak)
            previous = bucket
        self.assertLessEqual(max_streak, 2)

    def test_neutral_catalog_enforces_regional_caps(self):
        items = []
        for i in range(80):
            if i < 30:
                country = "США"
            elif i < 55:
                country = "Южная Корея"
            elif i < 65:
                country = "Великобритания"
            elif i < 72:
                country = "Франция"
            else:
                country = "Россия"
            items.append({
                "id": str(i),
                "country": country,
                "mediaType": "tv" if i % 2 else "movie",
                "category": "TV_SERIES" if i % 2 else "MOVIES",
            })
        result = _balanced_catalog_items(items, 40)
        asian = sum(x["country"] in {"Южная Корея", "Япония", "Китай", "Гонконг", "Тайвань", "Таиланд"} for x in result)
        north_america = sum(x["country"] in {"США", "Канада"} for x in result)
        self.assertLessEqual(asian, 8)
        self.assertLessEqual(north_america, 18)
        self.assertEqual(len(result), 40)

    def test_unknown_compact_metadata_stays_unknown_instead_of_fabricated(self):
        row = {
            "id": 999,
            "tmdb_id": 999,
            "media_type": "movie",
            "title": "Тест",
            "localized_ru_title": "Тест",
            "original_title": "Test",
            "alternative_titles": "[]",
            "year": 0,
            "rating": 0.0,
            "vote_count": 0,
            "vote_average": 0.0,
            "duration_minutes": 0,
            "seasons_count": 0,
            "episodes_count": 0,
            "season_episode_counts": "[]",
            "collection_id": 0,
            "poster_url": "https://example.invalid/poster.jpg",
            "backdrop_url": "https://example.invalid/backdrop.jpg",
            "genres": "[]",
            "cast": "[]",
            "director": "",
            "creators": "[]",
            "country": "",
            "category": "movies",
            "streams": "[]",
            "quality": "",
            "seeders": 0,
            "link_verified": 0,
            "link_updated_at": None,
            "imdb_id": "",
            "metadata_source": "",
        }
        media = map_row_to_media(row, compact=True)
        self.assertEqual(media["country"], "")
        self.assertEqual(media["durationMinutes"], 0)
        self.assertEqual(media["duration"], "")
        self.assertEqual(media["quality"], "Auto")
        self.assertEqual(media["ageRating"], 0)
        self.assertFalse(media["isNew"])


    def test_person_credit_scope_filters_professions(self):
        actor_credit = {"credit_type": "cast", "character": "Tyler Durden"}
        director_credit = {"credit_type": "crew", "department": "Directing", "job": "Director"}
        producer_credit = {"credit_type": "crew", "department": "Production", "job": "Producer"}
        creator_credit = {"credit_type": "crew", "department": "Creator", "job": "Creator"}

        self.assertEqual(_normalize_person_credit_scope("Актёр"), "actor")
        self.assertEqual(_normalize_person_credit_scope("Режиссёр"), "director")
        self.assertEqual(_normalize_person_credit_scope("Создатели"), "creator")
        self.assertTrue(_remote_credit_matches_scope(actor_credit, "actor"))
        self.assertFalse(_remote_credit_matches_scope(producer_credit, "actor"))
        self.assertTrue(_remote_credit_matches_scope(director_credit, "director"))
        self.assertFalse(_remote_credit_matches_scope(producer_credit, "director"))
        self.assertTrue(_remote_credit_matches_scope(creator_credit, "creator"))
        self.assertFalse(_remote_credit_matches_scope(actor_credit, "creator"))

    def test_local_person_credit_requires_exact_structured_name(self):
        row = {
            "cast": '[{"name":"Брэд Питт","role":"Tyler Durden"},{"name":"Питт Дэвис"}]',
            "director": "Дэвид Финчер, Второй Режиссёр",
            "creators": '["Алекс Хирш"]',
        }
        self.assertTrue(_row_has_exact_person_credit(row, ["Брэд Питт"], "actor"))
        self.assertFalse(_row_has_exact_person_credit(row, ["Брэд"], "actor"))
        self.assertFalse(_row_has_exact_person_credit(row, ["Tyler Durden"], "actor"))
        self.assertTrue(_row_has_exact_person_credit(row, ["Дэвид Финчер"], "director"))
        self.assertFalse(_row_has_exact_person_credit(row, ["Финчер"], "director"))
        self.assertTrue(_row_has_exact_person_credit(row, ["Алекс Хирш"], "creator"))
        self.assertFalse(_row_has_exact_person_credit(row, ["Алекс"], "creator"))

    def test_person_fallback_quotes_cast_column(self):
        import sqlite3
        import catalog_api

        conn = sqlite3.connect(":memory:")
        try:
            conn.execute(
                "CREATE TABLE movies (tmdb_id INTEGER, media_type TEXT, localized_ru_title TEXT, poster_url TEXT, director TEXT, \"cast\" TEXT, year INTEGER, rating REAL)"
            )
            conn.execute(
                "INSERT INTO movies VALUES (1, 'movie', 'Тест', 'https://example.invalid/p.jpg', '', ?, 2020, 7.0)",
                ('[{"name":"Брайан Крэнстон"}]',),
            )
            like = "%Брайан Крэнстон%"
            rows = conn.execute(
                f"SELECT * FROM movies WHERE {catalog_api._USER_VISIBLE_SQL} AND (director LIKE ? OR [cast] LIKE ?) ORDER BY year DESC, rating DESC LIMIT ?",
                (like, like, 10),
            ).fetchall()
            self.assertEqual(len(rows), 1)
        finally:
            conn.close()



if __name__ == "__main__":
    unittest.main()
