import unittest
from variant_coverage import variant_coverage

def matrix_rows(pairs, episode=1):
    return [{"source":"fixture","url":f"https://media.example/{i}.mp4","voice":v,
             "quality":q,"season":1,"episode":episode} for i,(v,q) in enumerate(pairs)]

class VariantMatrixTests(unittest.TestCase):
    def coverage(self,pairs):
        return variant_coverage(matrix_rows(pairs),media_type="tv",season=1,episode=1)

    def test_full_rectangle(self):
        c=self.coverage([(v,q) for v in ("Studio A","Studio B","Original") for q in ("480p","720p","1080p")])
        self.assertTrue(c.complete)
        self.assertEqual(9,c.voice_quality_pairs)
        self.assertEqual(3,c.voices_with_three_qualities)

    def test_one_missing_pair_is_incomplete(self):
        pairs=[(v,q) for v in ("Studio A","Studio B","Original") for q in ("480p","720p","1080p")]
        c=self.coverage(pairs[:-1])
        self.assertFalse(c.complete)
        self.assertTrue(c.marginal_complete)

    def test_three_per_voice_without_three_common_qualities_is_incomplete(self):
        pairs=[("Studio A",q) for q in ("480p","720p","1080p")]+[("Studio B",q) for q in ("720p","1080p","2160p")]+[("Original",q) for q in ("480p","1080p","2160p")]
        c=self.coverage(pairs)
        self.assertEqual(3,c.voices_with_three_qualities)
        self.assertFalse(c.complete)

    def test_subset_of_larger_matrix_can_complete(self):
        pairs=[(v,q) for v in ("Studio A","Studio B","Original") for q in ("480p","720p","1080p")]
        self.assertTrue(self.coverage(pairs+[("Studio C","2160p")]).complete)

    def test_wrong_episode_cannot_fill_missing_pair(self):
        pairs=[(v,q) for v in ("Studio A","Studio B","Original") for q in ("480p","720p","1080p")]
        rows=matrix_rows(pairs[:-1])+matrix_rows(pairs[-1:],episode=2)
        self.assertFalse(variant_coverage(rows,media_type="tv",season=1,episode=1).complete)
