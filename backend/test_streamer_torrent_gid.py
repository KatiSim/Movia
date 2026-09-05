import importlib
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import Mock, patch


# The snapshot intentionally contains streamer.py without the catalog runtime
# modules. Stub those imports so these tests exercise only the torrent-GID code.
for _module_name in ("catalog_api", "live_catalog_sync"):
    sys.modules.setdefault(_module_name, types.ModuleType(_module_name))

_cache_connection = Mock()
with patch("sqlite3.connect", return_value=_cache_connection):
    STREAMER = importlib.import_module("streamer")


INFO_HASH = "94f7" * 10


class FakeAria2:
    def __init__(self, active=None, waiting=None, status_by_gid=None, files_by_gid=None):
        self.active = list(active or [])
        self.waiting = list(waiting or [])
        self.status_by_gid = {
            str(task["gid"]): dict(task)
            for task in (status_by_gid or [])
        }
        for task in self.active + self.waiting:
            self.status_by_gid.setdefault(str(task["gid"]), dict(task))
        self.files_by_gid = {
            str(gid): [dict(item) for item in items]
            for gid, items in dict(files_by_gid or {}).items()
        }
        self.calls = []
        self.added = []
        self.removed = []

    def rpc(self, method, params, timeout):
        self.calls.append((method, params, timeout))
        if method == "aria2.tellActive":
            return [dict(task) for task in self.active]
        if method == "aria2.tellWaiting":
            return [dict(task) for task in self.waiting]
        if method == "aria2.tellStatus":
            gid = str(params[0])
            if gid not in self.status_by_gid:
                raise RuntimeError("unknown gid")
            return dict(self.status_by_gid[gid])
        if method == "aria2.getFiles":
            gid = str(params[0])
            if gid not in self.files_by_gid:
                raise RuntimeError("files unavailable")
            return [dict(item) for item in self.files_by_gid[gid]]
        if method == "aria2.forceRemove":
            self.removed.append(str(params[0]))
            return "OK"
        if method == "aria2.addUri":
            self.added.append(params)
            return "new-gid"
        raise AssertionError(f"unexpected aria2 method: {method}")


class TorrentGidTests(unittest.TestCase):
    def setUp(self):
        STREAMER._TORRENT_GIDS.clear()
        STREAMER._TORRENT_OWNED_GIDS.clear()
        self.temp_dir = tempfile.TemporaryDirectory()
        self.task_dir = Path(self.temp_dir.name)

    def tearDown(self):
        STREAMER._TORRENT_GIDS.clear()
        STREAMER._TORRENT_OWNED_GIDS.clear()
        self.temp_dir.cleanup()

    def call_helper(self, fake):
        with patch.object(STREAMER, "aria2_rpc", side_effect=fake.rpc):
            return STREAMER.get_or_create_torrent_gid(
                INFO_HASH,
                f"magnet:?xt=urn:btih:{INFO_HASH}",
                self.task_dir,
            )

    def test_process_restart_reuses_active_task_without_add_uri(self):
        fake = FakeAria2(active=[{
            "gid": "active-after-restart",
            "status": "active",
            "completedLength": "4096",
            "infoHash": INFO_HASH.upper(),
        }])

        gid = self.call_helper(fake)

        self.assertEqual(gid, "active-after-restart")
        self.assertEqual(fake.added, [])
        self.assertEqual(STREAMER._TORRENT_GIDS[INFO_HASH], gid)
        self.assertEqual(
            [call[0] for call in fake.calls[:2]],
            ["aria2.tellActive", "aria2.tellWaiting"],
        )

    def test_ranking_prefers_active_then_greater_completed_length(self):
        fake = FakeAria2(
            active=[
                {
                    "gid": "active-low",
                    "status": "active",
                    "completedLength": "10",
                    "infoHash": INFO_HASH,
                },
                {
                    "gid": "active-high",
                    "status": "active",
                    "completedLength": "20",
                    "infoHash": INFO_HASH,
                },
            ],
            waiting=[{
                "gid": "waiting-high",
                "status": "waiting",
                "completedLength": "9999",
                "infoHash": INFO_HASH,
            }],
        )

        gid = self.call_helper(fake)

        self.assertEqual(gid, "active-high")
        self.assertEqual(
            set(fake.removed),
            {"active-low", "waiting-high"},
        )
        self.assertEqual(fake.added, [])

    def test_duplicate_cleanup_force_removes_tasks_without_touching_files(self):
        retained_file = self.task_dir / "already-downloaded.mkv"
        retained_file.write_bytes(b"downloaded data")
        fake = FakeAria2(
            active=[{
                "gid": "canonical-active",
                "status": "active",
                "completedLength": "500",
                "infoHash": INFO_HASH,
            }, {
                "gid": "duplicate-active",
                "status": "active",
                "completedLength": "100",
                "infoHash": INFO_HASH,
            }],
            waiting=[{
                "gid": "duplicate-waiting",
                "status": "waiting",
                "completedLength": "0",
                "infoHash": INFO_HASH,
            }],
        )

        gid = self.call_helper(fake)

        self.assertEqual(gid, "canonical-active")
        self.assertEqual(
            fake.removed,
            ["duplicate-active", "duplicate-waiting"],
        )
        self.assertTrue(retained_file.exists())
        self.assertEqual(retained_file.read_bytes(), b"downloaded data")

    def test_worse_in_memory_mapping_is_replaced_by_existing_active_task(self):
        mapped_gid = "mapped-waiting"
        STREAMER._TORRENT_GIDS[INFO_HASH] = mapped_gid
        fake = FakeAria2(
            active=[{
                "gid": "better-active",
                "status": "active",
                "completedLength": "1",
                "infoHash": INFO_HASH,
            }],
            status_by_gid=[{
                "gid": mapped_gid,
                "status": "waiting",
                "completedLength": "100000",
                "infoHash": INFO_HASH,
            }],
        )

        gid = self.call_helper(fake)

        self.assertEqual(gid, "better-active")
        self.assertEqual(fake.removed, [mapped_gid])
        self.assertEqual(fake.added, [])
        self.assertEqual(STREAMER._TORRENT_GIDS[INFO_HASH], gid)

    def test_rediscovered_task_is_not_owned_or_removed_by_release_timeout(self):
        fake = FakeAria2(active=[{
            "gid": "rediscovered-active",
            "status": "active",
            "completedLength": "4096",
            "infoHash": INFO_HASH,
        }])
        with patch.object(STREAMER, "aria2_rpc", side_effect=fake.rpc):
            gid = STREAMER.get_or_create_torrent_gid(
                INFO_HASH, f"magnet:?xt=urn:btih:{INFO_HASH}", self.task_dir
            )
            STREAMER.release_torrent_gid(INFO_HASH, gid, remove_task=True)
        self.assertNotIn(gid, STREAMER._TORRENT_OWNED_GIDS)
        self.assertEqual(fake.removed, [])

    def test_new_task_is_owned_and_can_be_removed_by_release_timeout(self):
        fake = FakeAria2()
        with patch.object(STREAMER, "aria2_rpc", side_effect=fake.rpc):
            gid = STREAMER.get_or_create_torrent_gid(
                INFO_HASH, f"magnet:?xt=urn:btih:{INFO_HASH}", self.task_dir
            )
            self.assertIn(gid, STREAMER._TORRENT_OWNED_GIDS)
            STREAMER.release_torrent_gid(INFO_HASH, gid, remove_task=True)
        self.assertEqual(fake.removed, [gid])
        self.assertNotIn(gid, STREAMER._TORRENT_OWNED_GIDS)


class CompletedTorrentCacheTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)

    def tearDown(self):
        self.temp_dir.cleanup()

    @staticmethod
    def _write_complete_mp4(path: Path, size: int = 64 * 1024) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        header = b"\x00\x00\x00\x18ftypisom" + b"\x00" * 20
        with path.open("wb") as handle:
            handle.write(header)
            remaining = size - len(header)
            chunk = b"M" * 4096
            while remaining > 0:
                part = chunk[: min(len(chunk), remaining)]
                handle.write(part)
                remaining -= len(part)

    @staticmethod
    def _write_sparse_mp4(path: Path, logical_size: int = 8 * 1024 * 1024) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("wb") as handle:
            handle.write(b"\x00\x00\x00\x18ftypisom" + b"\x00" * 20)
            handle.truncate(logical_size)

    def test_sparse_placeholder_with_valid_header_is_not_complete(self):
        path = self.root / "show.s01e01.mp4"
        self._write_sparse_mp4(path)
        self.assertTrue(STREAMER.has_playable_container_head(path))
        self.assertFalse(STREAMER.is_physically_complete_file(path))
        self.assertIsNone(STREAMER.find_completed_cached_video(self.root, 1, 1))

    def test_fully_written_video_is_complete(self):
        path = self.root / "show.s01e01.mp4"
        self._write_complete_mp4(path)
        self.assertTrue(STREAMER.is_physically_complete_file(path))
        self.assertEqual(STREAMER.find_completed_cached_video(self.root, 1, 1), path)

    def test_exact_season_episode_match_never_falls_back_to_other_season(self):
        wrong = self.root / "show.s02e01.mp4"
        self._write_complete_mp4(wrong)
        self.assertTrue(STREAMER.episode_path_matches(wrong, 2, 1))
        self.assertFalse(STREAMER.episode_path_matches(wrong, 1, 1))
        self.assertIsNone(STREAMER.find_completed_cached_video(self.root, 1, 1))
        correct = self.root / "show.s01e01.mp4"
        self._write_complete_mp4(correct)
        self.assertEqual(STREAMER.find_completed_cached_video(self.root, 1, 1), correct)

    def test_stale_aria2_control_file_does_not_hide_complete_requested_episode(self):
        control = self.root / "season-pack.aria2"
        control.write_bytes(b"stale-control")
        path = self.root / "Season 01" / "show.s01e01.mp4"
        self._write_complete_mp4(path)
        self.assertEqual(STREAMER.find_completed_cached_video(self.root, 1, 1), path)


if __name__ == "__main__":
    unittest.main()



class MediaTaskProfileTests(unittest.TestCase):
    def setUp(self):
        STREAMER._TORRENT_GIDS.clear()
        STREAMER._TORRENT_OWNED_GIDS.clear()
        self.temp_dir = tempfile.TemporaryDirectory()
        self.task_dir = Path(self.temp_dir.name)

    def tearDown(self):
        STREAMER._TORRENT_GIDS.clear()
        STREAMER._TORRENT_OWNED_GIDS.clear()
        self.temp_dir.cleanup()

    def test_materialized_media_task_beats_metadata_only_task(self):
        info_hash = "ab12" * 10
        active = [
            {
                "gid": "metadata-task",
                "status": "active",
                "completedLength": "999999",
                "infoHash": info_hash,
            },
            {
                "gid": "media-task",
                "status": "active",
                "completedLength": "1",
                "infoHash": info_hash,
            },
        ]
        files = {
            "metadata-task": [
                {"index": "1", "path": "[METADATA] release info", "length": "41998"},
            ],
            "media-task": [
                {"index": "1", "path": "/cache/episode.s01e01.mkv", "length": "1000000"},
            ],
        }
        calls = []
        removed = []

        def rpc(method, params, timeout):
            calls.append(method)
            if method == "aria2.tellActive":
                return active
            if method == "aria2.tellWaiting":
                return []
            if method == "aria2.getFiles":
                return files[str(params[0])]
            if method == "aria2.forceRemove":
                removed.append(str(params[0]))
                return "OK"
            raise AssertionError(method)

        with patch.object(STREAMER, "aria2_rpc", side_effect=rpc):
            gid = STREAMER.get_or_create_torrent_gid(
                info_hash,
                f"magnet:?xt=urn:btih:{info_hash}",
                self.task_dir,
            )

        self.assertEqual(gid, "media-task")
        self.assertNotIn("metadata-task", removed)
        self.assertIn("aria2.getFiles", calls)

    def test_metadata_only_task_is_reused_while_follow_torrent_materializes(self):
        info_hash = "cd34" * 10
        fake = FakeAria2(
            active=[{
                "gid": "metadata-task",
                "status": "active",
                "completedLength": "1",
                "infoHash": info_hash,
                "followedBy": [],
            }],
            files_by_gid={
                "metadata-task": [
                    {"index": "1", "path": "[METADATA] release info", "length": "41998"},
                ],
            },
        )

        with patch.object(STREAMER, "aria2_rpc", side_effect=fake.rpc):
            gid = STREAMER.get_or_create_torrent_gid(
                info_hash,
                f"magnet:?xt=urn:btih:{info_hash}",
                self.task_dir,
            )

        self.assertEqual(gid, "metadata-task")
        self.assertEqual(fake.added, [])
        self.assertEqual(STREAMER._TORRENT_GIDS[info_hash], "metadata-task")

    def test_followed_by_media_child_is_adopted_atomically(self):
        info_hash = "ef56" * 10
        parent_gid = "metadata-parent"
        child_gid = "media-child"
        fake = FakeAria2(
            status_by_gid=[
                {
                    "gid": parent_gid,
                    "status": "complete",
                    "completedLength": "41998",
                    "infoHash": info_hash,
                    "followedBy": [child_gid],
                },
                {
                    "gid": child_gid,
                    "status": "active",
                    "completedLength": "1024",
                    "infoHash": info_hash,
                    "following": parent_gid,
                },
            ],
            files_by_gid={
                child_gid: [
                    {
                        "index": "1",
                        "path": "/cache/show.s01e01.mkv",
                        "length": "1000000",
                    },
                ],
            },
        )
        STREAMER._TORRENT_GIDS[info_hash] = parent_gid
        STREAMER._TORRENT_OWNED_GIDS.add(parent_gid)

        with patch.object(STREAMER, "aria2_rpc", side_effect=fake.rpc):
            resolved = STREAMER._followed_torrent_media_gid(info_hash, parent_gid)

        self.assertEqual(resolved, child_gid)
        self.assertEqual(STREAMER._TORRENT_GIDS[info_hash], child_gid)
        self.assertIn(child_gid, STREAMER._TORRENT_OWNED_GIDS)

    def test_followed_by_child_with_wrong_hash_is_rejected(self):
        info_hash = "ab78" * 10
        parent_gid = "metadata-parent"
        child_gid = "wrong-child"
        fake = FakeAria2(
            status_by_gid=[
                {
                    "gid": parent_gid,
                    "status": "complete",
                    "infoHash": info_hash,
                    "followedBy": [child_gid],
                },
                {
                    "gid": child_gid,
                    "status": "active",
                    "infoHash": "dead" * 10,
                },
            ],
            files_by_gid={
                child_gid: [
                    {"index": "1", "path": "/cache/wrong.mkv", "length": "1000000"},
                ],
            },
        )

        with patch.object(STREAMER, "aria2_rpc", side_effect=fake.rpc):
            resolved = STREAMER._followed_torrent_media_gid(info_hash, parent_gid)

        self.assertIsNone(resolved)


if __name__ == "__main__":
    unittest.main()

class TorrentTrackerRefreshTests(unittest.TestCase):
    def test_magnet_tracker_option_deduplicates_and_preserves_order(self):
        magnet = (
            "magnet:?xt=urn:btih:" + INFO_HASH
            + "&tr=https%3A%2F%2Fa.example%2Fannounce"
            + "&tr=https%3A%2F%2Fa.example%2Fannounce"
            + "&tr=udp%3A%2F%2Fb.example%3A1337%2Fannounce"
        )
        self.assertEqual(
            STREAMER._magnet_tracker_option(magnet),
            "https://a.example/announce,udp://b.example:1337/announce",
        )

    def test_reused_metadata_task_receives_refreshed_tracker_pool(self):
        STREAMER._TORRENT_GIDS.clear()
        STREAMER._TORRENT_OWNED_GIDS.clear()
        fake = FakeAria2(
            active=[{
                "gid": "metadata-task",
                "status": "active",
                "completedLength": "0",
                "infoHash": INFO_HASH,
            }],
            files_by_gid={
                "metadata-task": [{"index": "1", "path": "[METADATA] x", "length": "123"}],
            },
        )
        original_rpc = fake.rpc
        changes = []
        def rpc(method, params, timeout):
            if method == "aria2.changeOption":
                changes.append(params)
                return "OK"
            return original_rpc(method, params, timeout)
        with tempfile.TemporaryDirectory() as tmp, patch.object(STREAMER, "aria2_rpc", side_effect=rpc):
            gid = STREAMER.get_or_create_torrent_gid(
                INFO_HASH,
                "magnet:?xt=urn:btih:" + INFO_HASH + "&tr=https%3A%2F%2Ffallback.example%2Fannounce",
                Path(tmp),
            )
        self.assertEqual(gid, "metadata-task")
        self.assertEqual(len(changes), 1)
        self.assertEqual(changes[0][1]["bt-tracker"], "https://fallback.example/announce")
