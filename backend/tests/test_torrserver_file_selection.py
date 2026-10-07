import ast
import re
import time
import unittest
import urllib.parse
from pathlib import Path
from typing import Optional, Dict, Any
from unittest.mock import Mock
from torrserver_file_selection import select_concrete_file_id


class TorrServerFileSelectionTests(unittest.TestCase):
    def file(self, path="Movie.mkv", id=1, length=100):
        return {"path": path, "id": id, "length": length}

    def select(self, files, season=None, episode=None, index=None):
        return select_concrete_file_id({"file_stats": files}, season=season, episode=episode,
            file_index=index, is_media=lambda p: Path(str(p)).suffix.lower() in {".mp4", ".mkv"},
            episode_matches=lambda p, s, e: f"S{s:02d}E{e:02d}" in str(p))

    def test_single_movie_uses_actual_engine_id(self):
        self.assertEqual("17", self.select([self.file(id=17)]))

    def test_movie_text_and_subtitles_do_not_create_video_ambiguity(self):
        self.assertEqual("3", self.select([self.file("Movie.srt", 1), self.file("Movie.mkv", 3)]))

    def test_multiple_videos_are_not_selected_by_size_or_order(self):
        self.assertIsNone(self.select([self.file(length=9999), self.file("Sample.mp4", 2)]))

    def test_missing_metadata_is_not_a_source(self):
        self.assertIsNone(self.select([]))
        self.assertIsNone(self.select("unknown"))

    def test_foreign_file_indexes_are_not_reinterpreted(self):
        for index in (0, 1, 17):
            self.assertIsNone(self.select([self.file(id=17)], index=index))

    def test_series_is_exact_episode(self):
        files=[self.file("Show.S01E01.mkv", 3), self.file("Show.S01E02.mkv", 8)]
        self.assertEqual("8", self.select(files, 1, 2))
        self.assertIsNone(self.select(files, 1, 3))

    def test_duplicate_episode_is_ambiguous(self):
        self.assertIsNone(self.select([self.file("Show.S01E02.mkv", 1), self.file("Alt.S01E02.mkv", 2)],1,2))

    def test_subtitle_episode_is_not_video(self):
        self.assertIsNone(self.select([self.file("Show.S01E02.srt")],1,2))

    def test_partial_and_invalid_episode_scope_fail_closed(self):
        for season,episode in [(1,None),(None,2),(0,2),(1,0)]:
            self.assertIsNone(self.select([self.file()],season,episode))

    def test_invalid_actual_engine_ids_are_rejected(self):
        for id in [None, 0, -1, True, "../1", "a", "١"]:
            self.assertIsNone(self.select([self.file(id=id)]))

    def test_unknown_video_length_does_not_hide_ambiguity(self):
        self.assertIsNone(self.select([self.file(),self.file("Other.mp4",2,None)]))
        self.assertIsNone(self.select([self.file(length="invalid")]))


class TorrServerPrepareStreamTests(unittest.TestCase):
    def setUp(self):
        # Import only the actual sidecar helper definitions, without starting
        # streamer background services or accessing a real catalog during tests.
        source=Path(__file__).parents[1]/"runtime/streamer.py"
        tree=ast.parse(source.read_text())
        names={"_torrserver_prepare_stream", "_torrserver_prepare_episode_stream"}
        nodes=[n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name in names]
        self.hash="a"*40
        self.file={"hash":self.hash,"file_stats":[{"path":"Movie.mkv","id":3,"length":100}]}
        self.post=Mock(return_value=self.file);self.add=Mock(return_value=True)
        self.ns=dict(Optional=Optional,Dict=Dict,Any=Any,re=re,time=time,urllib=urllib,
            TORRSERVER_DISCOVERY_SECONDS=.1,TORRSERVER_RPC_TIMEOUT_SECONDS=.05,
            TORRENT_DISCOVERY_SLEEP_SECONDS=.01,TORRSERVER_URL="http://127.0.0.1:18090",
            _torrserver_enabled=lambda:True,_normalize_torrent_info_hash=lambda h:str(h).lower(),
            _torrserver_post=self.post,_torrserver_add_magnet=self.add,
            _torrserver_concrete_file_id=lambda info,s,e,index=None: select_concrete_file_id(info,
                season=s,episode=e,file_index=index,is_media=lambda p:str(p).endswith(".mkv"),
                episode_matches=lambda p,s,e:f"S{s:02d}E{e:02d}" in str(p)))
        exec(compile(ast.Module(body=nodes,type_ignores=[]),str(source),"exec"),self.ns)

    def test_known_movie_route_uses_verified_hash_and_engine_id(self):
        self.assertEqual(f"http://127.0.0.1:18090/play/{self.hash}/3",
                         self.ns["_torrserver_prepare_stream"](self.hash,"magnet",None,None))
        self.add.assert_not_called()

    def test_wrong_response_hash_cannot_supply_a_file(self):
        self.post.return_value={**self.file,"hash":"b"*40}
        self.assertIsNone(self.ns["_torrserver_prepare_stream"](self.hash,"magnet",None,None))

    def test_explicit_foreign_index_does_not_start_another_engine(self):
        self.assertIsNone(self.ns["_torrserver_prepare_stream"](self.hash,"magnet",None,None,file_index=0))
        self.post.assert_not_called();self.add.assert_not_called()

    def test_existing_episode_wrapper_cannot_be_used_as_a_movie(self):
        self.assertIsNone(self.ns["_torrserver_prepare_episode_stream"](self.hash,"magnet",None,None))
        self.post.assert_not_called()

    def test_add_timeout_is_bounded_by_remaining_discovery_budget(self):
        self.post.return_value={};self.add.return_value=False
        self.assertIsNone(self.ns["_torrserver_prepare_stream"](self.hash,"magnet",None,None,timeout_sec=.02))
        self.assertGreater(self.add.call_args.kwargs["timeout"],0)
        self.assertLessEqual(self.add.call_args.kwargs["timeout"],.02)
