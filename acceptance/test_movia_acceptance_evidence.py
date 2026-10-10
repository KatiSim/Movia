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
