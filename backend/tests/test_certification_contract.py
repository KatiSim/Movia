import unittest
from tmdb_client import russian_certification
class CertificationTests(unittest.TestCase):
    def test_unknown_is_nullable(self):self.assertIsNone(russian_certification({},'movie')['age_rating'])
    def test_foreign_system_is_not_mapped_to_ru(self):self.assertIsNone(russian_certification({'content_ratings':{'results':[{'iso_3166_1':'US','rating':'TV-MA'}]}},'tv')['age_rating'])
    def test_explicit_zero_plus_is_preserved(self):self.assertEqual(0,russian_certification({'content_ratings':{'results':[{'iso_3166_1':'RU','rating':'0+'}]}},'tv')['age_rating'])
    def test_conflicting_ru_releases_choose_restrictive_known_rating(self):
        d={'release_dates':{'results':[{'iso_3166_1':'RU','release_dates':[{'certification':'12+'},{'certification':'18+'}]}]}}
        self.assertEqual(18,russian_certification(d,'movie')['age_rating'])
    def test_source_and_jurisdiction_are_explicit(self):
        result=russian_certification({'content_ratings':{'results':[{'iso_3166_1':'RU','rating':'16+'}]}},'tv')
        self.assertEqual({'age_rating':16,'age_rating_source':'tmdb_certification','age_rating_jurisdiction':'RU'},result)
