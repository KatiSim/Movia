from pathlib import Path
C=Path.home()/'.cache/movia-architecture-20261002';R=C/'repo'
p=R/'backend/runtime/provider_discovery.py';s=p.read_text()
s=s.replace('    error_count: int = 0\n', '    error_count: int = 0\n    pending_futures: tuple = ()\n',1)
s=s.replace('            if outcome.streams:\n                on_provider_result(ProviderDiscoveryOutcome(\n                    [dict(row) for row in outcome.streams], outcome.status,\n                    outcome.providers, outcome.error_count))', '            on_provider_result(ProviderDiscoveryOutcome(\n                [dict(row) for row in outcome.streams], outcome.status,\n                outcome.providers, outcome.error_count))')
s=s.replace('        statuses.append(result.status)\n    errors = len(pending)', '        statuses.append(result.status)\n    errors = sum(result.error_count for result in cached)')
s=s.replace('tuple(providers), errors)\n    if pending:', 'tuple(providers), errors, tuple(pending))\n    if pending:')
s=s.replace('ProviderDiscoveryOutcome([], "PROVIDER_TIMEOUT", tuple(providers), errors)', 'ProviderDiscoveryOutcome([], "PROVIDER_TIMEOUT", tuple(providers), errors, tuple(pending))')
p.write_text(s)
p=R/'backend/runtime/streamer.py';s=p.read_text()
a=s.index('def _resolve_clean_provider_registry(');b=s.index('\ndef _resolve_balancer_provider(',a);v=s[a:b]
v=v.replace(') -> List[Dict[str, Any]]:', '):',1).replace('        return outcome.streams','        return outcome')
v=v.replace('print(f"[DEBUG] Clean provider registry error: {type(exc).__name__}: {exc}")\n        return []', 'print(f"[DEBUG] Clean provider registry error: {type(exc).__name__}")\n        from provider_discovery import ProviderDiscoveryOutcome\n        return ProviderDiscoveryOutcome([], "PROVIDER_ERROR", error_count=1)')
s=s[:a]+v+s[b:]
s=s.replace('print(f"Torrent resolve error: {exc}")\n        return []','print(f"Torrent resolve error: {type(exc).__name__}")\n        raise')
s=s.replace('print(f"[DEBUG] Balancer query error: {exc}")\n        return []','print(f"[DEBUG] Balancer query error: {type(exc).__name__}")\n        raise')
# Only the active resolver uses this trace; other list-based consumers remain compatible.
a=s.index('def resolve_on_demand_streams(');b=s.index('\ndef _persist_late_provider_results(',a);v=s[a:b]
v=v.replace('    clean_title =', '    from discovery_outcome import DiscoveryTrace, ResolvedStreams, result_streams\n    clean_title =',1)
v=v.replace('completed.set_result(outcome.streams)', 'completed.set_result(outcome)')
v=v.replace('                _persist_late_provider_results(\n                    completed, cache_key, dict(catalog_identity), season, episode)', '                _persist_late_provider_results(\n                    completed, cache_key, dict(catalog_identity), season, episode)')
needle='            direct_budget_started = time.monotonic()'
assert needle in v
v=v.replace(needle,'''            branches = {"balancer":balancer_future,"registry":registry_future}
            if P2P_ENABLED and not CLOUD_MODE:branches["torrent"] = torrent_future
            trace = DiscoveryTrace(branches,validator=lambda rows:bool(
                filter_streams_for_episode(_scope_streams_to_catalog_card(
                    rows,catalog_identity,season,episode),season,episode)))
            direct_budget_started = time.monotonic()''')
v=v.replace('direct_streams = balancer_future.result(timeout=4.0) if balancer_future else []', 'direct_streams = result_streams(balancer_future.result(timeout=4.0)) if balancer_future else []')
v=v.replace('registry_streams = registry_future.result(timeout=remaining_registry_budget) if registry_future else []', 'registry_streams = result_streams(registry_future.result(timeout=remaining_registry_budget)) if registry_future else []')
v=v.replace('torrent_streams = torrent_future.result(timeout=torrent_timeout) if torrent_future else []', 'torrent_streams = result_streams(torrent_future.result(timeout=torrent_timeout)) if torrent_future else []')
v=v.replace('return get_cached_streams(cache_key) or streams', 'return ResolvedStreams(get_cached_streams(cache_key) or streams,trace=trace)')
s=s[:a]+v+s[b:]
s=s.replace('        fresh = _scope_streams_to_catalog_card(future.result(), identity, season, episode)', '        from discovery_outcome import result_streams\n        fresh = _scope_streams_to_catalog_card(result_streams(future.result()), identity, season, episode)')
p.write_text(s)
p=R/'backend/runtime/discovery_queue.py';s=p.read_text()
s=s.replace('import time\n','import time\nfrom discovery_outcome import DiscoveryJobResult\n',1)
a=s.index('        old = self.completed.get(key)');b=s.index('        return "STOPPED"',a)
s=s[:a]+'''        old = self.completed.get(key)
        if old:
            if self.clock() < old[1]:
                state,expires,error,job = old
                if job is not None:
                    state,error = self._job_status(job)
                    if state != old[0]:
                        if state == "ERROR":self.errors += 1
                        if state in {"ERROR","UNAVAILABLE"}:self.failures += 1
                        expires = self.clock() + (self.success_ttl if state == "READY" else self.failure_ttl)
                        self.completed[key] = (state,expires,error,job)
                return state
            del self.completed[key]
'''+s[b:]
a=s.index('            error_type = None');b=s.index('                self.condition.notify_all()',a)
s=s[:a]+'''            error_type = None
            job = None
            try:
                result = self.resolver(key)
                if isinstance(result,DiscoveryJobResult):
                    job = result
                    state,error_type = self._job_status(job)
                else:state = "READY" if bool(result) else "UNAVAILABLE"
            except Exception as error:
                state = "ERROR"
                error_type = type(error).__name__[:80]
            with self.condition:
                self.active.remove(key)
                ttl = self.success_ttl if state == "READY" else self.failure_ttl
                self.completed[key] = (state,self.clock()+max(0.0,ttl),error_type,job)
                self.completed.move_to_end(key)
                while len(self.completed) > self.remembered:self.completed.popitem(last=False)
                self.finished += 1
                self.failures += int(state in {"ERROR","UNAVAILABLE"})
                self.errors += int(state == "ERROR")
'''+s[b:]
a=s.index('    def error(')
s=s[:a]+'''    @staticmethod
    def _job_status(job):
        if job.error:return "ERROR",job.error
        detail = job.trace.snapshot() if job.trace is not None else {}
        if detail.get("pendingProviderCount",0):return "PENDING",None
        if detail.get("providerErrorCount",0):
            statuses=detail.get("providerStatuses",[])
            code="DISCOVERY_TIMEOUT" if "DISCOVERY_TIMEOUT" in statuses else "PROVIDER_ERROR"
            return "ERROR",code
        return ("READY",None) if job.ready or detail.get("hasScopedResults") else ("UNAVAILABLE",None)

    def details(self,key):
        with self.condition:
            self._status(key)
            old=self.completed.get(key)
            if old and old[3] is not None and old[3].trace is not None:
                return old[3].trace.snapshot()
            return {}

'''+s[a:];p.write_text(s)
p=R/'backend/runtime/catalog_stream_service.py';s=p.read_text()
s=s.replace('from discovery_queue import DiscoveryQueue','from discovery_queue import DiscoveryQueue\nfrom discovery_outcome import DiscoveryJobResult')
s=s.replace('{"QUEUED", "RUNNING"}','{"QUEUED", "RUNNING", "PENDING"}')
s=s.replace('        if discovery == "ERROR":', '        response.update({k:v for k,v in self.queue.details(key).items() if k != "hasScopedResults"})\n        if discovery == "ERROR":',1)
s=s.replace('        rows = self._rows(card, rows, season, episode)\n        if not rows: return False', '        trace = getattr(rows,"discovery_trace",None)\n        rows = self._rows(card, rows, season, episode)\n        if not rows:return DiscoveryJobResult(False,trace=trace) if trace is not None else False')
s=s.replace('        return bool(self.runtime.persist_resolved_streams_to_catalog(card["id"], rows))', '        persisted = bool(self.runtime.persist_resolved_streams_to_catalog(card["id"],rows))\n        if not persisted:raise RuntimeError("catalog_persistence_failed")\n        return DiscoveryJobResult(True,trace=trace) if trace is not None else True')
p.write_text(s)
p=R/'backend/tests/test_on_demand_provider_registry.py';s=p.read_text()
s=s.replace('        self.assertEqual(len(rows), 1)\n        self.assertEqual(rows[0]["catalog_media_id"], "native-budget-test")', '        self.assertEqual(rows.status,"OK")\n        self.assertEqual(len(rows.streams), 1)\n        self.assertEqual(rows.streams[0]["catalog_media_id"], "native-budget-test")')
p.write_text(s)
print('Typed registry outcomes and late-provider traces reach the bounded catalog queue')
