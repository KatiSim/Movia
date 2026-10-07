import unittest,threading
from unittest.mock import patch
from concurrent.futures import wait as real_wait
import provider_discovery as module

class NestedProviderPublicationTests(unittest.TestCase):
    def setUp(self):
        with module._PROVIDER_CACHE_LOCK:module._PROVIDER_CACHE.clear()
        self.env={flag:"0" for flag in module._PROVIDER_FLAGS}
        self.env[module._PROVIDER_FLAGS[0]]="1"
        self.env[module._PROVIDER_FLAGS[1]]="1"
    def outcome(self,name):
        return module.ProviderDiscoveryOutcome([{"source":name,"url":"https://cdn.example/"+name+".mp4",
            "voice":name,"quality":"720p"}],"OK",(name,))
    def test_running_provider_finishing_after_budget_is_published_without_second_request(self):
        release=threading.Event();started=threading.Event();published=threading.Event();seen=[]
        def discover(*,enabled_flags,**request):
            if module._PROVIDER_FLAGS[1] in enabled_flags:
                started.set();release.wait(2);return self.outcome("late")
            return self.outcome("fast")
        def receive(outcome):
            seen.extend(row["voice"] for row in outcome.streams)
            if "late" in seen:published.set()
        def bounded_wait(futures,timeout):
            self.assertTrue(started.wait(1))
            return real_wait(futures,timeout=.03)
        try:
            with patch.dict(module.os.environ,self.env),patch.object(module,"_discover_provider_streams",side_effect=discover),patch.object(module,"wait",side_effect=bounded_wait):
                result=module.discover_provider_streams(title="Film",media_id="7",media_type="movie",
                    on_provider_result=receive,budget_seconds=.1)
                self.assertEqual(["fast"],[row["voice"] for row in result.streams])
                self.assertEqual(["fast"],seen)
                release.set()
                self.assertTrue(published.wait(1))
                self.assertEqual({"fast","late"},set(seen))
        finally:release.set()
    def test_publication_callback_cannot_mutate_cached_outcome(self):
        original=self.outcome("fast")
        def receive(result):result.streams[0]["voice"]="changed"
        with patch.dict(module.os.environ,{**self.env,module._PROVIDER_FLAGS[1]:"0"}),patch.object(module,"_discover_provider_streams",return_value=original):
            result=module.discover_provider_streams(title="Film",media_id="7",on_provider_result=receive)
        self.assertEqual("fast",result.streams[0]["voice"])
        self.assertEqual("fast",original.streams[0]["voice"])
    def test_cached_completed_provider_is_published_to_new_exact_request(self):
        request={"title":"Film","media_id":"7","media_type":"movie"}
        with patch.dict(module.os.environ,{**self.env,module._PROVIDER_FLAGS[1]:"0"}),patch.object(module,"_discover_provider_streams",return_value=self.outcome("cached")):
            module.discover_provider_streams(**request)
            seen=[]
            result=module.discover_provider_streams(**request,on_provider_result=lambda value:seen.append(value.streams[0]["voice"]))
            self.assertEqual(["cached"],seen)
            self.assertEqual("cached",result.streams[0]["voice"])
    def test_publication_failure_does_not_erase_discovery_result(self):
        def failed(value):raise OSError("controlled persistence failure")
        with patch.dict(module.os.environ,{**self.env,module._PROVIDER_FLAGS[1]:"0"}),patch.object(module,"_discover_provider_streams",return_value=self.outcome("fast")):
            result=module.discover_provider_streams(title="Film",media_id="7",on_provider_result=failed)
        self.assertEqual("OK",result.status)
        self.assertEqual("fast",result.streams[0]["voice"])
    def test_empty_provider_result_never_creates_synthetic_leaf(self):
        with patch.dict(module.os.environ,{**self.env,module._PROVIDER_FLAGS[1]:"0"}),patch.object(module,"_discover_provider_streams",return_value=module.ProviderDiscoveryOutcome([],"NO_RESULTS")):
            seen=[]
            result=module.discover_provider_streams(title="Film",media_id="7",on_provider_result=seen.append)
        self.assertEqual([],seen);self.assertEqual([],result.streams)
