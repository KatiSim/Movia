import io,json,tempfile,time,unittest
from pathlib import Path
from unittest.mock import Mock,patch
import streamer
from source_playback_evidence import sanitize_media3_failure_payload,source_runtime_evidence
from playback_availability_index import PlaybackAvailabilityService

class FailurePayloadTest(unittest.TestCase):
    def payload(self,**changes):
        return {"sourceId":"src:one","reason":"NETWORK","observedAt":95,**changes}
    def test_transient_failure_has_cooldown(self):
        self.assertTrue(sanitize_media3_failure_payload(self.payload(),100)["cooldown"])
    def test_non_network_failure_is_distinct(self):
        self.assertFalse(sanitize_media3_failure_payload(self.payload(reason="NON_NETWORK"),100)["cooldown"])
    def test_invalid_reason_cannot_leak_locator(self):
        for reason in ["https://private.test/token",[],None,"UNKNOWN"]:
            with self.subTest(reason=type(reason).__name__),self.assertRaises(ValueError):
                sanitize_media3_failure_payload(self.payload(reason=reason),100)
    def test_observation_time_is_bounded_and_numeric(self):
        for observed in [True,float("nan"),float("inf"),106,-201,"95"]:
            with self.subTest(observed=repr(observed)),self.assertRaises(ValueError):
                sanitize_media3_failure_payload(self.payload(observedAt=observed),100)
    def test_source_id_must_address_indexed_source(self):
        for ident in ["other:one","src:one\nprivate","src:","src:"+"x"*125,None,["src:one"]]:
            with self.subTest(ident=type(ident).__name__),self.assertRaises(ValueError):
                sanitize_media3_failure_payload(self.payload(sourceId=ident),100)
    def test_failure_cannot_submit_measurements(self):
        with self.assertRaises(ValueError):
            sanitize_media3_failure_payload(self.payload(actualQuality="1080p"),100)
    def test_cooldown_expires_without_claiming_new_playback(self):
        source={"sourceId":"src:one","verificationStatus":"COOLDOWN","verificationMethod":"MEDIA3_SUCCESS",
                "lastFailureAt":100,"lastSuccessAt":90,"consecutiveFailures":1}
        self.assertEqual("COOLDOWN",source_runtime_evidence(source,129)["verificationStatus"])
        after=source_runtime_evidence(source,130)
        self.assertEqual("DISCOVERED",after["verificationStatus"])
        self.assertFalse(after["decodedPlayback"])
        self.assertIsNone(after["startupLatencyMs"])
    def test_expired_url_is_not_reenabled_by_expiring_cooldown(self):
        source={"verificationStatus":"COOLDOWN","lastFailureAt":100,"expiresAt":125}
        self.assertEqual("EXPIRED",source_runtime_evidence(source,130)["verificationStatus"])

class FailureStorageTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.index=PlaybackAvailabilityService(Path(self.tmp.name)/"truth.db")
        self.index.verify_candidate("1",{"url":"https://fixture.test/a.mp4","provider":"fixture","quality":"Auto"},
            kind="movie",verification_method="MEDIA3_SUCCESS",actual_quality="576p",success=True,now=100)
        with self.index.repository.connection() as conn:
            self.source=conn.execute("SELECT source_id FROM playback_sources").fetchone()[0]
    def row(self):return dict(self.index.repository.get_source_row(self.source))
    def test_actual_failure_keeps_measurement_and_removes_current_success_status(self):
        self.index.record_source_failure(self.source,reason="NETWORK",cooldown=True,observed_at=110)
        row=self.row();self.assertEqual("COOLDOWN",row["verification_status"])
        self.assertEqual("576p",row["actual_quality"]);self.assertEqual(1,row["consecutive_failures"])
        self.assertEqual(100,row["last_success_at"])
    def test_late_failure_cannot_overwrite_newer_decoding(self):
        self.index.record_playback_success(self.source,actual_quality="576p",now=130)
        self.index.record_source_failure(self.source,reason="NETWORK",cooldown=True,observed_at=120)
        row=self.row();self.assertEqual("VERIFIED",row["verification_status"])
        self.assertEqual(0,row["consecutive_failures"]);self.assertEqual(130,row["last_success_at"])
    def test_duplicate_older_failure_does_not_increment_count(self):
        self.index.record_source_failure(self.source,reason="NETWORK",cooldown=True,observed_at=120)
        self.index.record_source_failure(self.source,reason="NETWORK",cooldown=True,observed_at=110)
        self.assertEqual(1,self.row()["consecutive_failures"])
    def test_unregistered_source_never_mutates_existing_source(self):
        with self.assertRaises(KeyError):
            self.index.record_source_failure("src:missing",reason="NETWORK",observed_at=110)
        self.assertEqual("VERIFIED",self.row()["verification_status"])

class FailureHttpTest(unittest.TestCase):
    def run_request(self,payload,authorized=True,loopback=True,index=None):
        raw=json.dumps(payload).encode()
        handler=object.__new__(streamer.StreamRequestHandler)
        handler.path="/internal/playback-availability/media3-failure"
        handler.client_address=("127.0.0.1",123)
        handler.headers={"Authorization":"Bearer fixture","Content-Type":"application/json","Content-Length":str(len(raw))}
        handler.rfile=io.BytesIO(raw);handler._send_json=Mock()
        target=index or Mock()
        target.record_source_failure.return_value={"mediaKey":"movie:1","status":"COOLDOWN"}
        with patch.object(streamer,"_is_loopback_client_address",return_value=loopback),patch.object(streamer,"_authorized_native_feedback",return_value=authorized),patch.object(streamer,"PLAYBACK_SOURCE_TRUTH_INDEX",target):
            handler._handle_post_internal()
        return handler._send_json.call_args.args,target
    def payload(self):return {"sourceId":"src:one","reason":"NETWORK","observedAt":time.time()}
    def test_authenticated_failure_routes_to_exact_source(self):
        (code,body),index=self.run_request(self.payload())
        self.assertEqual(200,code);self.assertEqual("MEDIA3_FAILURE",body["verificationMethod"])
        self.assertEqual("src:one",index.record_source_failure.call_args.args[0])
        self.assertTrue(index.record_source_failure.call_args.kwargs["cooldown"])
    def test_authentication_required_before_write(self):
        (code,_),index=self.run_request(self.payload(),authorized=False)
        self.assertEqual(401,code);index.record_source_failure.assert_not_called()
    def test_loopback_required_before_write(self):
        (code,_),index=self.run_request(self.payload(),loopback=False)
        self.assertEqual(403,code);index.record_source_failure.assert_not_called()
    def test_measurement_in_failure_is_rejected_without_write(self):
        (code,_),index=self.run_request({**self.payload(),"actualQuality":"1080p"})
        self.assertEqual(400,code);index.record_source_failure.assert_not_called()
    def test_unknown_source_returns_404(self):
        index=Mock();index.record_source_failure.side_effect=KeyError("src:one")
        (code,_),_=self.run_request(self.payload(),index=index)
        self.assertEqual(404,code)
