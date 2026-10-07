"""Non-secret, bounded-lifetime status for an exact discovery invocation.

Lists stay compatible with existing playback consumers. The catalog worker also
keeps the trace so a late provider error is not converted into no sources.
No transport I/O or database writes occur while polling a trace.
"""
from dataclasses import dataclass
from concurrent.futures import CancelledError
import re
import threading
import time


@dataclass(frozen=True)
class DiscoveryJobResult:
    ready: bool
    trace: object = None
    error: str = None


class ResolvedStreams(list):
    def __init__(self, rows=(), *, trace=None):
        super().__init__(rows)
        self.discovery_trace = trace


def result_streams(result):
    return list(getattr(result, "streams", result) or [])


class DiscoveryTrace:
    def __init__(self, branches, *, validator=lambda rows:bool(rows), deadline_seconds=30, clock=time.monotonic):
        self.clock = clock
        self.deadline = clock() + deadline_seconds
        self.validator = validator
        self.branches = dict(branches)
        self.lock = threading.Lock()
        self.extra_errors = set()

    def add_error(self, code):
        # Only fixed codes are accepted; exception messages/locators are not evidence fields.
        if code not in {"PERSISTENCE_ERROR","LATE_RESULT_ERROR"}:
            raise ValueError("invalid_discovery_error")
        with self.lock:self.extra_errors.add(code)

    def snapshot(self):
        with self.lock:
            errors = len(self.extra_errors)
            pending = 0
            ready = False
            statuses = set(self.extra_errors)
            todo = list(self.branches.items())
            while todo:
                name, item = todo.pop()
                if item is None:
                    item = ("EXECUTOR_BUSY",1,False,())
                    self.branches[name] = item
                elif not isinstance(item,tuple):
                    if not item.done():
                        pending += 1
                        continue
                    try:
                        result = item.result()
                        rows = result_streams(result)
                        deferred = tuple(getattr(result,"pending_futures",()) or ())
                        status = str(getattr(result,"status","OK" if rows else "NO_RESULTS"))
                        if not re.fullmatch(r"[A-Z_]{1,64}",status):
                            status = "PROVIDER_ERROR"
                        count = max(0,int(getattr(result,"error_count",0)))
                        # A budget expiry is still pending work, not a completed provider error.
                        if deferred and status == "PROVIDER_TIMEOUT":status = "NO_RESULTS"
                        has_rows = bool(self.validator(rows))
                        item = (status,count,has_rows,deferred)
                    except CancelledError:
                        item = ("PROVIDER_CANCELLED",1,False,())
                    except Exception:
                        item = ("PROVIDER_ERROR",1,False,())
                    self.branches[name] = item
                    for index, future in enumerate(item[3]):
                        child = name+".late."+str(index)
                        if child not in self.branches:
                            self.branches[child] = future
                            todo.append((child,future))
                    # Drop strong references to completed futures and their URL-bearing payloads.
                    self.branches[name] = item[:3]+((),)
                status,count,has_rows,_ = item
                errors += count
                ready |= has_rows
                statuses.add(status)
            if pending and self.clock() >= self.deadline:
                statuses.add("DISCOVERY_TIMEOUT")
                errors += 1
                pending = 0
            return {"providerErrorCount":errors,"pendingProviderCount":pending,
                    "providerStatuses":sorted(statuses),"hasScopedResults":ready}
