import sqlite3
import unittest

from catalog.metadata import MetadataEngine, SourcePolicy
from catalog_intelligence_api import CatalogIntelligenceApi


def make_db():
    conn = sqlite3.connect(":memory:")
    conn.row_factory = sqlite3.Row
    conn.executescript(
        """
        CREATE TABLE movies(
            id INTEGER PRIMARY KEY,
            tmdb_id INTEGER,
            media_type TEXT NOT NULL DEFAULT 'movie',
            title TEXT NOT NULL,
            original_title TEXT DEFAULT '',
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
        INSERT INTO movies(id,tmdb_id,title,original_title,year,synopsis,genres,poster_url,backdrop_url)
        VALUES(1,101,'Base title','Original title',2025,'Base synopsis','["Drama"]','poster.jpg','backdrop.jpg');
        INSERT INTO movies(id,tmdb_id,title,original_title,year,synopsis,genres)
        VALUES(2,102,'Second project','Second original',2023,'Second synopsis','["Thriller"]');
        """
    )
    return conn


class MetadataEngineTest(unittest.TestCase):
    def setUp(self):
        self.conn = make_db()
        self.engine = MetadataEngine(self.conn)
        self.engine.register_source(SourcePolicy("source_a", "Source A", 0.9))
        self.engine.register_source(SourcePolicy("source_b", "Source B", 0.8))
        self.engine.register_source(SourcePolicy("source_c", "Source C", 0.4))

    def tearDown(self):
        self.conn.close()

    def test_trusted_consensus_prefers_supported_value(self):
        self.engine.observe(1, "source_a", {"title": "Consensus title", "rating": 8.0})
        self.engine.observe(1, "source_b", {"title": "Consensus title", "rating": 9.0})
        self.engine.observe(1, "source_c", {"title": "Different title", "rating": 2.0})

        title = self.engine.consensus_field(1, "title")
        rating = self.engine.consensus_field(1, "rating")

        self.assertEqual(title["value"], "Consensus title")
        self.assertEqual(set(title["sources"]), {"source_a", "source_b"})
        self.assertEqual(title["method"], "trusted_weighted_consensus")
        self.assertAlmostEqual(rating["value"], 7.24, places=2)
        self.assertEqual(rating["method"], "trusted_weighted_average")

    def test_full_card_exposes_provenance_and_relational_cast(self):
        self.engine.observe(
            1,
            "source_a",
            {"synopsis": "Authoritative synopsis", "genres": ["Drama", "Mystery"]},
        )
        self.engine.record_credit(
            1,
            person_id="tmdb:7",
            name="Actor One",
            role_type="cast",
            role_name="Detective",
            billing_order=0,
            profile_url="actor.jpg",
        )

        card = self.engine.full_card(1)

        self.assertEqual(card["synopsis"], "Authoritative synopsis")
        self.assertEqual(card["genres"], ["Drama", "Mystery"])
        self.assertEqual(card["cast"][0]["name"], "Actor One")
        self.assertEqual(card["cast"][0]["role_name"], "Detective")
        self.assertIn("synopsis", card["metadata_provenance"])
        self.assertFalse(card["availability"]["playable"])
        self.assertEqual(card["metadata_completeness"], 1.0)

    def test_actor_projects_support(self):
        for media_id, role_name in ((1, "Lead"), (2, "Guest")):
            self.engine.record_credit(
                media_id,
                person_id="tmdb:42",
                name="Actor Two",
                role_type="cast",
                role_name=role_name,
                billing_order=1,
            )

        result = self.engine.person_projects(person_id="tmdb:42")

        self.assertEqual(result["person"]["name"], "Actor Two")
        self.assertEqual([item["id"] for item in result["projects"]], [1, 2])
        self.assertEqual(result["projects"][0]["role_name"], "Lead")

    def test_api_not_found_contract(self):
        api = CatalogIntelligenceApi(self.conn)
        self.assertEqual(api.media_card(999)["error"], "not_found")


if __name__ == "__main__":
    unittest.main()
