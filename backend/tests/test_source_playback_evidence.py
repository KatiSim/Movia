import unittest
from source_playback_evidence import source_runtime_evidence
from playback_availability import summarize_streams

class SourcePlaybackEvidenceTest(unittest.TestCase):
    def source(self, **changes):
        return dict(sourceId="src:one", verificationStatus="VERIFIED",
            verificationMethod="MEDIA3_SUCCESS", healthScore=1,
            consecutiveFailures=0, startupLatencyMs=1200, expiresAt=None, **changes)

    def test_decoded_success_exposes_latency_and_quality_evidence_separately(self):
        result=source_runtime_evidence(self.source(), 100)
        self.assertTrue(result["decodedPlayback"])
        self.assertEqual(1200, result["startupLatencyMs"])
        self.assertNotIn("quality", result)

    def test_manifest_only_is_not_decoder_success(self):
        source=self.source();source["verificationMethod"]="HLS_MANIFEST"
        result=source_runtime_evidence(source,100)
        self.assertFalse(result["decodedPlayback"])
        self.assertIsNone(result["startupLatencyMs"])

    def test_expired_verified_source_cannot_authorize_success(self):
        source=self.source();source["expiresAt"]=110
        result=source_runtime_evidence(source,100,15)
        self.assertEqual("EXPIRED",result["verificationStatus"])
        self.assertFalse(result["decodedPlayback"])
        self.assertIsNone(result["startupLatencyMs"])

    def test_failed_keeps_failure_evidence_without_success_latency(self):
        source=self.source();source.update(verificationStatus="COOLDOWN",consecutiveFailures=3,healthScore=.1)
        result=source_runtime_evidence(source,100)
        self.assertEqual(3,result["consecutiveFailures"])
        self.assertEqual(.1,result["healthScore"])
        self.assertFalse(result["decodedPlayback"])

    def test_invalid_numeric_evidence_is_conservative(self):
        source=self.source();source.update(healthScore=float("nan"),startupLatencyMs=float("inf"),expiresAt="bad")
        result=source_runtime_evidence(source,100)
        self.assertEqual(.5,result["healthScore"])
        self.assertEqual("EXPIRED",result["verificationStatus"])

    def test_http200_valid_playlist_is_only_inspected(self):
        row={"url":"https://cdn.example/full.m3u8","transport":"hls",
             "transport_metadata":{"manifest_verified":True,"available_qualities":["720p"]}}
        summary=summarize_streams([row])
        self.assertEqual("MANIFEST_INSPECTED",summary["status"])
        self.assertEqual(0,summary["verifiedDirectCount"])
        self.assertEqual(1,summary["inspectedManifestCount"])

    def test_real_decoder_evidence_confirms_direct_without_manifest(self):
        row={"url":"https://cdn.example/film.mp4","transport":"direct",
             "sourceTruth":source_runtime_evidence(self.source(),100)}
        summary=summarize_streams([row])
        self.assertEqual("VERIFIED_DIRECT",summary["status"])
        self.assertEqual(1,summary["verifiedDirectCount"])

    def test_failed_playlist_cannot_be_promoted_by_manifest_inspection(self):
        source=self.source();source["verificationStatus"]="FAILED"
        row={"url":"https://cdn.example/full.m3u8","transport":"hls",
             "transport_metadata":{"manifest_verified":True},
             "sourceTruth":source_runtime_evidence(source,100)}
        self.assertEqual(0,summarize_streams([row])["verifiedDirectCount"])

class PhysicalAudioMappingTest(unittest.TestCase):
    def setUp(self):
        self.ru={"name":"DUB", "language":"ru"}
        self.en={"name":"English", "language":"en"}
    def map(self,row,tracks):
        from source_playback_evidence import map_manifest_audio_identity
        return map_manifest_audio_identity(row,tracks)
    def test_plain_ordinal_cannot_map_reordered_decoder_tracks(self):
        self.assertIsNone(self.map({"audio_track_index":0},[self.en,self.ru]))
    def test_manifest_track_identity_survives_decoder_reordering(self):
        row={"audio_track_index":0,"transport_metadata":{"manifest_verified":True,"manifest_audio_track":self.ru}}
        self.assertEqual(self.ru,self.map(row,[self.en,self.ru]))
    def test_duplicate_label_and_language_remains_ambiguous(self):
        row={"transport_metadata":{"manifest_verified":True,"manifest_audio_track":self.ru}}
        self.assertIsNone(self.map(row,[self.ru,self.ru]))
    def test_provider_studio_name_alone_is_not_physical_evidence(self):
        self.assertIsNone(self.map({"voice":"DUB","audio_track_index":0},[self.ru,self.en]))
    def test_unverified_track_metadata_cannot_supply_mapping(self):
        self.assertIsNone(self.map({"transport_metadata":{"manifest_audio_track":self.ru}},[self.ru,self.en]))
    def test_manifest_and_actual_track_language_must_agree(self):
        row={"transport_metadata":{"manifest_verified":True,"manifest_audio_track":self.ru}}
        self.assertIsNone(self.map(row,[{"name":"DUB","language":"en"}]))
