"""Deterministic proof that catalog coverage never masquerades as decoded playback."""
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from acceptance import movia_acceptance as a


class AcceptanceEvidenceTests(unittest.TestCase):
    def test_no_source_is_not_a_player_failure(self):
        d={'streamSelection':{'fallbackReason':'NO_SOURCE'}}
        self.assertEqual('NO_SOURCE',a.playback_failure_cause(operation_ok=False,operation_detail='operation FAILED',diagnostics_payload=d))
        checks=[{'domain':'COVERAGE','status':'FAIL','passed':False},
                {'domain':'PLAYER','status':'BLOCKED','passed':False,'blockedBy':'NO_SOURCE'},
                {'domain':'PLAYER','status':'PASS','passed':True}]
        metrics=a.evidence_metrics(checks)
        self.assertEqual({'total':2,'evaluated':1,'passed':1,'failed':0,'blocked':1},metrics['player'])
        self.assertEqual(1,metrics['coverage']['failed'])

    def test_no_false_success_from_operation_timeout(self):
        self.assertEqual('OPERATION_TIMEOUT',a.playback_failure_cause(operation_ok=False,operation_detail='operation TIMEOUT'))
        self.assertEqual('PLAYBACK_FAILED',a.playback_failure_cause(operation_ok=False,operation_detail='player failed',diagnostics_payload={'streamSelection':{'fallbackReason':'PLAYER_ERROR'}}))
        self.assertEqual('OK',a.playback_failure_cause(operation_ok=True))

    def test_historical_first_frame_control_must_be_fresh_and_unexpired(self):
        with tempfile.TemporaryDirectory() as tmp:
            db=Path(tmp)/'evidence.db'
            with sqlite3.connect(db) as c:
                c.executescript('''CREATE TABLE playback_availability(media_key TEXT,media_id TEXT,media_kind TEXT,availability_status TEXT);
                    CREATE TABLE playback_sources(media_key TEXT,verification_method TEXT,last_success_at REAL,source_type TEXT,expires_at REAL);''')
                cases=[('good','MOVIE','VERIFIED','MEDIA3_SUCCESS',999_900,'HLS',None),
                       ('expired','MOVIE','VERIFIED','MEDIA3_SUCCESS',999_900,'HLS',999_500),
                       ('old','MOVIE','VERIFIED','MEDIA3_SUCCESS',100,'MP4',None),
                       ('manifest_only','MOVIE','VERIFIED','HTTP_MANIFEST',999_900,'HLS',None),
                       ('episode','EPISODE','VERIFIED','MEDIA3_SUCCESS',999_900,'HLS',None),
                       ('p2p','MOVIE','VERIFIED','MEDIA3_SUCCESS',999_900,'P2P',None)]
                for mid,kind,status,method,last,transport,expires in cases:
                    c.execute('INSERT INTO playback_availability VALUES(?,?,?,?)',(mid,mid,kind,status))
                    c.execute('INSERT INTO playback_sources VALUES(?,?,?,?,?)',(mid,method,last,transport,expires))
            with patch.object(a,'SOURCE_TRUTH_DB',db):
                self.assertEqual(['good'],a.select_recent_first_frame_movies(now=1_000_000))

    def test_synchronous_agent_quality_action_is_success_not_missing_operation(self):
        response={'status':'completed','action':'player.selectQuality','requestedQuality':'720p'}
        with patch.object(a,'action',return_value=(200,response,'')) as action, \
             patch.object(a,'poll',side_effect=AssertionError('sync action has no operation')):
            ok,detail,payload=a.accepted_operation('player.selectQuality',{'quality':'720p'})
        self.assertTrue(ok)
        self.assertEqual('',detail)
        self.assertEqual(response,payload)
        action.assert_called_once()

    def test_verified_adjacent_episodes_are_required_for_series_control(self):
        with tempfile.TemporaryDirectory() as temp:
            db=Path(temp)/'episodes.db'
            with sqlite3.connect(db) as c:
                c.executescript("""CREATE TABLE playback_availability(media_key TEXT,media_id TEXT,media_kind TEXT,
                  season_number INTEGER,episode_number INTEGER,availability_status TEXT,last_success_at REAL);
                  CREATE TABLE playback_sources(media_key TEXT,verification_method TEXT,last_success_at REAL,source_type TEXT);""")
                for episode in (1,2):
                    key=f'series:159:s001:e{episode:04d}'
                    c.execute('INSERT INTO playback_availability VALUES(?,?,?,?,?,?,?)',
                              (key,'159','EPISODE',1,episode,'VERIFIED',999_900.0))
                    c.execute('INSERT INTO playback_sources VALUES(?,?,?,?)',
                              (key,'MEDIA3_SUCCESS',999_900.0,'HLS'))
                c.execute('INSERT INTO playback_availability VALUES(?,?,?,?,?,?,?)',
                          ('series:160:s001:e0001','160','EPISODE',1,1,'VERIFIED',999_900))
            with patch.object(a,'SOURCE_TRUTH_DB',db):
                self.assertEqual([('159',1,1)],a.select_verified_episode_pairs(now=1_000_000))
                with sqlite3.connect(db) as c:
                    c.execute('UPDATE playback_sources SET verification_method=? WHERE media_key=?',
                              ('MANIFEST','series:159:s001:e0002'))
                self.assertEqual([],a.select_verified_episode_pairs(now=1_000_000))

    def test_catalog_sample_visibility_policy_matches_backend(self):
        import runpy
        project_root = Path(__file__).resolve().parent.parent
        policy = runpy.run_path(str(project_root / 'backend/runtime/catalog_sql.py'))
        self.assertEqual(' '.join(policy['USER_VISIBLE_SQL'].split()),
                         ' '.join(a.USER_VISIBLE_SQL.split()))

    def test_headless_frame_probe_always_gets_detached(self):
        runner=a.Runner.__new__(a.Runner)
        runner.checks=[]; runner.verbose=False; runner.samples={};runner.sample_seed=11
        runner.sample_selection=lambda: None
        runner.backend=lambda: None
        runner.android=lambda: None
        runner.first_frame_control=lambda: None
        runner.series=lambda: None
        with patch.object(a,'action',return_value=(200,{'status':'completed'},'')) as agent, \
             patch.object(a,'reset_player'):
            summary=runner.run()
        self.assertEqual(0,summary['total'])
        agent.assert_called_with('player.probeSurface',{'enabled':False})

    def test_strict_gate_never_converts_blocked_to_pass(self):
        runner=a.Runner.__new__(a.Runner)
        runner.verbose=False
        runner.checks=[]
        runner.record('ANDROID','random playback',False,domain='COVERAGE',cause='NO_SOURCE')
        runner.record('ANDROID','Media3 READY',False,blocked_by='NO_SOURCE')
        self.assertFalse(runner.checks[0]['passed'])
        self.assertFalse(runner.checks[1]['passed'])
        self.assertEqual('BLOCKED',runner.checks[1]['status'])
        self.assertEqual(0,a.evidence_metrics(runner.checks)['player']['failed'])


if __name__ == '__main__':
    unittest.main()
