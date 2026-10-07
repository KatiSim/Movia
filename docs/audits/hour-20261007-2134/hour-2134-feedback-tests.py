from pathlib import Path
C=Path.home()/'.cache/movia-architecture-20261002';R=C/'repo'
p=R/'backend/tests/test_native_variant_feedback.py'
s=p.read_text()+'''
class CachedNativeFailureTests(unittest.TestCase):
    setUp=NativeVariantFeedbackTests.setUp
    record=NativeVariantFeedbackTests.record

    def failure(self,payload=None):
        import time
        from native_variant_feedback import record_native_variant_failure
        request=dict(self.payload,observation={"reason":"NETWORK","observedAt":time.time()})
        return record_native_variant_failure(self.index,payload or request,
            lambda *args:copy.deepcopy(self.card),lambda rows,card:rows)

    def test_unindexed_cached_leaf_records_failure_without_faking_verification(self):
        result=self.failure()
        source=next(x for x in result["sources"] if x["sourceId"]==result["sourceId"])
        self.assertEqual("COOLDOWN",source["verificationStatus"])
        self.assertEqual("NONE",source["verificationMethod"])
        self.assertEqual(1,source["consecutiveFailures"])
        self.assertIsNone(source["actualQuality"])
        self.assertIsNone(source["lastSuccessAt"])

    def test_decoder_failure_is_not_transient_network_cooldown(self):
        import time
        result=self.failure(dict(self.payload,observation={"reason":"NON_NETWORK","observedAt":time.time()}))
        self.assertEqual("FAILED",result["sources"][0]["verificationStatus"])

    def test_wrong_profile_failure_does_not_create_a_source(self):
        with self.assertRaises(ValueError):
            self.failure(dict(self.payload,profileHash="0"*64,observation={"reason":"NETWORK","observedAt":__import__('time').time()}))
        self.assertIsNone(self.index.get("42",kind="MOVIE"))

    def test_failure_cannot_accept_caller_locator(self):
        import time
        with self.assertRaises(ValueError):
            self.failure(dict(self.payload,url="https://other.example/a.mp4",observation={"reason":"NETWORK","observedAt":time.time()}))

    def test_failure_rejects_wrong_episode_and_catalog_without_mutation(self):
        import time
        for changes in [{"mediaId":"43"},{"season":1,"episode":2},{"streamId":"provider-item:v2:other"}]:
            with self.subTest(changes=changes),self.assertRaises(ValueError):
                self.failure(dict(self.payload,**changes,observation={"reason":"NETWORK","observedAt":time.time()}))
        self.assertIsNone(self.index.get("42",kind="MOVIE"))

    def test_existing_different_profile_is_not_reset_by_old_cached_failure(self):
        changed=copy.deepcopy(self.row);changed["headers"]={"Referer":"https://fresh.example"}
        before=self.index.record_discovery("42",[changed],kind="MOVIE")
        with self.assertRaises(ValueError):self.failure()
        self.assertEqual(before,self.index.get("42",kind="MOVIE"))

    def test_out_of_order_failure_keeps_newer_decoded_success(self):
        import time
        now=time.time()
        self.record()
        self.index.record_playback_success(self.index.get("42",kind="MOVIE")["sources"][0]["sourceId"],now=now+1)
        result=self.failure(dict(self.payload,observation={"reason":"NETWORK","observedAt":now-1}))
        source=result["sources"][0]
        self.assertEqual("VERIFIED",source["verificationStatus"])
        self.assertEqual(0,source["consecutiveFailures"])
        self.assertEqual("480p",source["actualQuality"])

    def test_same_profile_failure_keeps_previous_failure_count(self):
        self.failure()
        result=self.failure()
        self.assertEqual(2,result["sources"][0]["consecutiveFailures"])

    def test_newer_success_between_select_and_update_cannot_be_overwritten(self):
        import time
        from contextlib import contextmanager
        from unittest.mock import patch
        source_id=self.record()["sourceId"];stamp=time.time()
        original=self.index.repository.connection;index=self.index;injected=[False]
        class Cursor:
            def __init__(self,real):self.real=real
            def fetchone(self):
                rows=self.real.fetchall();injected[0]=True
                index.record_playback_success(source_id,now=stamp+1)
                return rows[0] if rows else None
        class Connection:
            def __init__(self,real):self.real=real
            def execute(self,sql,params=()):
                cursor=self.real.execute(sql,params)
                if not injected[0] and sql.startswith("SELECT * FROM playback_sources WHERE source_id="):
                    return Cursor(cursor)
                return cursor
        @contextmanager
        def connection():
            with original() as real:yield Connection(real)
        with patch.object(self.index.repository,"connection",connection),self.assertRaises(ValueError):
            self.index.record_source_failure(source_id,reason="NETWORK",observed_at=stamp)
        source=self.index.get("42",kind="MOVIE")["sources"][0]
        self.assertEqual("VERIFIED",source["verificationStatus"])
        self.assertEqual(0,source["consecutiveFailures"])

'''
p.write_text(s)
p=R/'app/src/test/java/app/movia/android/domain/playback/NativeVariantFeedbackTest.kt';s=p.read_text()
i=s.rfind('}')
s=s[:i]+'''
    @Test fun scopedFeedbackAttachesSourceIdWithoutChangingIdentity() {
        val current = leaf().copy(quality="480p")
        val attached = current.withNativeFeedbackSourceId(leaf(),"src:decoded")
        assertEquals("src:decoded",attached.sourceId)
        assertEquals(current.stableStreamId,attached.stableStreamId)
        assertEquals(current.quality,attached.quality)
    }
    @Test fun staleReplyCannotAttachToRotatedUrlOrChangedHeaders() {
        val prepared = leaf()
        for (current in listOf(prepared.copy(url="https://cdn.example/new.mp4"),
            prepared.copy(headers=mapOf("Referer" to "https://new.example")))) {
            assertEquals(current,current.withNativeFeedbackSourceId(prepared,"src:old"))
        }
    }
    @Test fun feedbackCannotAttachToAnotherMovieOrEpisode() {
        val prepared=leaf().copy(seasonNumber=1,episodeNumber=2)
        for (current in listOf(prepared.copy(catalogMediaId="43"),prepared.copy(episodeNumber=3),
            prepared.copy(stableStreamId="provider-item:v2:other"))) {
            assertEquals(current,current.withNativeFeedbackSourceId(prepared,"src:old"))
        }
    }
    @Test fun malformedResponseDoesNotAttachSourceId() {
        assertEquals(leaf(),leaf().withNativeFeedbackSourceId(leaf(),"https://invalid.example/source"))
    }
'''+s[i:];p.write_text(s)
print('Added 9 failure-scope/backend and 4 source-ID/Android regressions')
