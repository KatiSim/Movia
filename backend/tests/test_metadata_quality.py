import unittest

from metadata_quality import bayesian_rating
from catalog_api import _balanced_catalog_items, _catalog_region_bucket, map_row_to_media


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


if __name__ == "__main__":
    unittest.main()
