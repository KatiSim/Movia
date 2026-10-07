import copy,tempfile,unittest
from pathlib import Path
from native_variant_feedback import attach_feedback_scope,feedback_fingerprints,record_native_variant_success
from playback_availability_index import PlaybackAvailabilityService
from stream_validation import bind_stream_identity
import streamer

class NativeVariantFeedbackTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.index=PlaybackAvailabilityService(Path(self.tmp.name)/"truth.db")
        self.card={"id":"42","title":"Test","year":2024,"media_type":"movie"}
        raw={"url":"https://cdn.example/film.mp4","source":"HDRezka","provider":"HDRezka",
            "quality":"Не указано","voice":"Original","transport":"direct",
            "stream_id":"provider-item:v2:scope:leaf","headers":{"Referer":"https://provider.example"}}
        self.card["streams"]=bind_stream_identity([raw],catalog_media_id="42",title="Test",year=2024,media_type="movie")
        self.row=self.card["streams"][0]
        fp=feedback_fingerprints(self.row)
        self.payload={"mediaId":"42","season":None,"episode":None,"streamId":self.row["stream_id"],
            "locatorHash":fp["native_feedback_locator_hash"],"profileHash":fp["native_feedback_profile_hash"],
            "observation":{"actualQuality":"480p","actualQualities":["480p"],"startupLatencyMs":1000}}
    def record(self,payload=None):
        return record_native_variant_success(self.index,payload or self.payload,
            lambda *args:copy.deepcopy(self.card),lambda rows,card:rows,streamer._sanitize_media3_success_payload)
    def test_cached_native_leaf_gets_durable_decoder_measurement(self):
        self.assertIsNone(self.index.get("42",kind="MOVIE"))
        result=self.record()
        source=next(x for x in result["sources"] if x["sourceId"]==result["sourceId"])
        self.assertEqual("VERIFIED",source["verificationStatus"])
        self.assertEqual("MEDIA3_SUCCESS",source["verificationMethod"])
        self.assertEqual("480p",source["actualQuality"])
    def test_caller_cannot_submit_arbitrary_locator(self):
        with self.assertRaises(ValueError):self.record(dict(self.payload,url="https://evil.example/a.mp4"))
    def test_rotated_url_cannot_receive_previous_frames(self):
        self.card["streams"][0]["url"]="https://cdn.example/new.mp4"
        with self.assertRaises(ValueError):self.record()
    def test_changed_request_profile_cannot_receive_previous_frames(self):
        self.card["streams"][0]["headers"]={"Referer":"https://changed.example"}
        with self.assertRaises(ValueError):self.record()
    def test_other_variant_or_catalog_id_is_rejected(self):
        for changes in [{"streamId":"provider-item:v2:scope:other"},{"mediaId":"43"}]:
            with self.subTest(changes=changes),self.assertRaises(ValueError):self.record(dict(self.payload,**changes))
    def test_episode_coordinates_cannot_be_fabricated_for_movie(self):
        with self.assertRaises(ValueError):self.record(dict(self.payload,season=1,episode=2))
    def test_numeric_boolean_episode_coordinates_are_rejected(self):
        with self.assertRaises(ValueError):self.record(dict(self.payload,season=True,episode=2))
    def test_exact_episode_is_recorded_under_its_own_key(self):
        self.card["media_type"]="tv"
        self.card["streams"][0].update(season=1,episode=2)
        self.payload.update(season=1,episode=2)
        self.assertEqual("series:42:s001:e0002",self.record()["mediaKey"])
        self.assertIsNone(self.index.get("42",kind="EPISODE",season=1,episode=3))
    def test_feedback_scope_metadata_does_not_expose_locator_or_headers(self):
        meta=attach_feedback_scope(self.row)["transport_metadata"]
        self.assertEqual(2,len(meta))
        self.assertTrue(all(len(x)==64 for x in meta.values()))
        self.assertNotIn(self.row["url"],str(meta))


class NativeMeasurementProfileStorageTests(unittest.TestCase):
    setUp=NativeVariantFeedbackTests.setUp
    record=NativeVariantFeedbackTests.record
    def test_changed_profile_discovery_does_not_reuse_old_decoder_proof(self):
        self.record()
        changed=copy.deepcopy(self.row);changed["headers"]={"Referer":"https://other.example"}
        result=self.index.record_discovery("42",[changed],kind="MOVIE")
        source=result["sources"][0]
        self.assertEqual("DISCOVERED",source["verificationStatus"])
        self.assertIsNone(source["actualQuality"])
        self.assertEqual([],source["actualQualities"])
    def test_overlay_does_not_publish_evidence_under_different_headers(self):
        from unittest.mock import patch
        self.record()
        changed=copy.deepcopy(self.row);changed["headers"]={"Referer":"https://other.example"}
        with patch.object(streamer,"PLAYBACK_SOURCE_TRUTH_INDEX",self.index):
            rows=streamer._annotate_streams_with_source_truth(self.card,[changed],None,None)
        self.assertNotIn("sourceTruth",rows[0])
        self.assertNotIn("sourceId",rows[0])
    def test_same_profile_discovery_keeps_decoded_measurement(self):
        self.record()
        result=self.index.record_discovery("42",[self.row],kind="MOVIE")
        self.assertEqual("VERIFIED",result["sources"][0]["verificationStatus"])
        self.assertEqual("480p",result["sources"][0]["actualQuality"])

    def test_failure_memory_is_scoped_to_the_request_profile(self):
        source_id=self.record()["sourceId"]
        self.index.record_source_failure(source_id,reason="NETWORK",cooldown=True)
        changed=copy.deepcopy(self.row);changed["headers"]={"Referer":"https://other.example"}
        source=self.index.record_discovery("42",[changed],kind="MOVIE")["sources"][0]
        self.assertEqual(0,source["consecutiveFailures"])
        self.assertIsNone(source["startupLatencyMs"])
        self.assertIsNone(source["lastFailureAt"])


class ScopedNativeFailureTests(unittest.TestCase):
    setUp=NativeVariantFeedbackTests.setUp
    record=NativeVariantFeedbackTests.record
    def test_delayed_error_cannot_fail_a_changed_profile(self):
        source_id=self.record()["sourceId"]
        old=feedback_fingerprints(self.row)
        changed=copy.deepcopy(self.row);changed["headers"]={"Referer":"https://changed.example"}
        before=self.index.record_discovery("42",[changed],kind="MOVIE")["sources"][0]
        with self.assertRaises(ValueError):
            self.index.record_source_failure(source_id,reason="NETWORK",cooldown=True,
                expected_locator_hash=old["native_feedback_locator_hash"],expected_profile_hash=old["native_feedback_profile_hash"])
        after=self.index.get("42",kind="MOVIE")["sources"][0]
        self.assertEqual(before,after)
    def test_wrong_locator_cannot_receive_error(self):
        source_id=self.record()["sourceId"]
        with self.assertRaises(ValueError):
            self.index.record_source_failure(source_id,reason="NETWORK",expected_locator_hash="0"*64)
        self.assertEqual("VERIFIED",self.index.get("42",kind="MOVIE")["sources"][0]["verificationStatus"])
    def test_matching_profile_records_actual_error(self):
        source_id=self.record()["sourceId"];fp=feedback_fingerprints(self.row)
        source=self.index.record_source_failure(source_id,reason="NETWORK",cooldown=True,
            expected_locator_hash=fp["native_feedback_locator_hash"],expected_profile_hash=fp["native_feedback_profile_hash"])["sources"][0]
        self.assertEqual("COOLDOWN",source["verificationStatus"])
        self.assertEqual(1,source["consecutiveFailures"])
    def test_failure_payload_accepts_both_valid_scope_hashes(self):
        from source_playback_evidence import sanitize_media3_failure_payload
        payload={"sourceId":"src:one","reason":"NETWORK","observedAt":100,"locatorHash":"a"*64,"profileHash":"b"*64}
        clean=sanitize_media3_failure_payload(payload,now=100)
        self.assertEqual(payload["locatorHash"],clean["locatorHash"])
        self.assertEqual(payload["profileHash"],clean["profileHash"])
    def test_failure_payload_cannot_supply_half_a_scope(self):
        from source_playback_evidence import sanitize_media3_failure_payload
        with self.assertRaises(ValueError):
            sanitize_media3_failure_payload({"sourceId":"src:one","reason":"NETWORK","observedAt":100,"profileHash":"b"*64},now=100)
    def test_failure_payload_rejects_noncanonical_scope_hash(self):
        from source_playback_evidence import sanitize_media3_failure_payload
        with self.assertRaises(ValueError):
            sanitize_media3_failure_payload({"sourceId":"src:one","reason":"NETWORK","observedAt":100,"locatorHash":"a"*64,"profileHash":"B"*64},now=100)

    def test_profile_change_between_read_and_write_cannot_receive_old_failure(self):
        from contextlib import contextmanager
        from unittest.mock import patch
        source_id=self.record()["sourceId"];old=feedback_fingerprints(self.row)
        changed=copy.deepcopy(self.row);changed["headers"]={"Referer":"https://changed-between-read-write.example"}
        original=self.index.repository.connection
        index=self.index;injected=[False]
        class Cursor:
            def __init__(self,real):self.real=real
            def fetchone(self):
                rows=self.real.fetchall();injected[0]=True
                index.record_discovery("42",[changed],kind="MOVIE")
                return rows[0] if rows else None
        class Connection:
            def __init__(self,real):self.real=real
            def execute(self,sql,params=()):
                cursor=self.real.execute(sql,params)
                if not injected[0] and sql.startswith("SELECT * FROM playback_sources WHERE source_id="):
                    return Cursor(cursor)
                return cursor
        @contextmanager
        def interleaved_connection():
            with original() as real:yield Connection(real)
        with patch.object(self.index.repository,"connection",interleaved_connection),self.assertRaises(ValueError):
            self.index.record_source_failure(source_id,reason="NETWORK",cooldown=True,
                expected_locator_hash=old["native_feedback_locator_hash"],expected_profile_hash=old["native_feedback_profile_hash"])
        source=self.index.get("42",kind="MOVIE")["sources"][0]
        self.assertEqual("DISCOVERED",source["verificationStatus"])
        self.assertEqual(0,source["consecutiveFailures"])
