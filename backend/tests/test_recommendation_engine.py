import sqlite3
import unittest

from catalog.metadata import MetadataEngine
from catalog.recommendation import RecommendationEngine
from catalog_intelligence_api import CatalogIntelligenceApi


def make_db():
    conn = sqlite3.connect(":memory:")
    conn.row_factory = sqlite3.Row
    conn.executescript(
        """
        CREATE TABLE movies(
            id INTEGER PRIMARY KEY,
            title TEXT NOT NULL,
            original_title TEXT DEFAULT '',
            media_type TEXT NOT NULL DEFAULT 'movie',
            year INTEGER DEFAULT 0,
            rating REAL DEFAULT 0,
            vote_average REAL DEFAULT 0,
            vote_count INTEGER DEFAULT 0,
            duration_minutes INTEGER DEFAULT 0,
            synopsis TEXT DEFAULT '',
            poster_url TEXT DEFAULT '',
            backdrop_url TEXT DEFAULT '',
            genres TEXT DEFAULT '[]',
            cast TEXT DEFAULT '[]',
            director TEXT DEFAULT '',
            country TEXT DEFAULT '',
            creators TEXT DEFAULT '[]',
            alternative_titles TEXT DEFAULT '[]',
            localized_ru_title TEXT DEFAULT '',
            seasons_count INTEGER DEFAULT 0,
            episodes_count INTEGER DEFAULT 0,
            season_episode_counts TEXT DEFAULT '[]'
        );
        INSERT INTO movies VALUES
          (1,'Target','Target','movie',2025,8.0,8.0,100,100,'','','','["Drama","Mystery"]','[]','','','[]','[]','',0,0,'[]'),
          (2,'Best','Best','movie',2024,8.5,8.5,100,110,'','','','["Drama","Mystery"]','[]','','','[]','[]','',0,0,'[]'),
          (3,'Genre only','Genre only','movie',2023,9.0,9.0,100,120,'','','','["Drama"]','[]','','','[]','[]','',0,0,'[]'),
          (4,'Cast only','Cast only','movie',2022,7.0,7.0,100,90,'','','','["Comedy"]','[]','','','[]','[]','',0,0,'[]');
        """
    )
    return conn


class RecommendationEngineTest(unittest.TestCase):
    def setUp(self):
        self.conn = make_db()
        self.metadata = MetadataEngine(self.conn)
        for media_id in (1, 2, 4):
            self.metadata.record_credit(
                media_id,
                person_id="tmdb:actor-1",
                name="Shared Actor",
                role_type="cast",
                billing_order=0,
            )
        self.engine = RecommendationEngine(self.conn, self.metadata)
        self.engine.refresh_all()

    def tearDown(self):
        self.conn.close()

    def test_genre_and_cast_candidate_ranks_first(self):
        items = self.engine.recommend(1, limit=10)
        self.assertEqual(items[0]["id"], 2)
        self.assertEqual(items[0]["reason"]["shared_genres"], ["drama", "mystery"])
        self.assertEqual(items[0]["reason"]["shared_cast"], ["Shared Actor"])
        self.assertEqual({item["id"] for item in items}, {2, 3, 4})

    def test_metadata_genre_observation_refreshes_api_index(self):
        api = CatalogIntelligenceApi(self.conn)
        api.rebuild_recommendation_index()
        api.record_metadata_observation(
            media_id=4,
            source_id="movia_editorial",
            fields={"genres": ["Drama", "Mystery"]},
        )
        result = api.recommendations_for(1, limit=10)
        cast_only = next(item for item in result["items"] if item["id"] == 4)
        self.assertEqual(cast_only["reason"]["shared_genres"], ["drama", "mystery"])

    def test_recommendation_index_is_rebuildable(self):
        stats = self.engine.refresh_all()
        self.assertEqual(stats["media_count"], 4)
        self.assertEqual(stats["genre_links"], 6)


if __name__ == "__main__":
    unittest.main()
