from pathlib import Path
import sys,json,time,datetime,os,subprocess,re
C=Path.home()/'.cache/movia-architecture-20261002';D=C/'repo/docs/audits/hour-20261008-0850';sys.path.insert(0,str(D))
from runtime_helpers import req,act
out={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'installedApkSha256':json.loads((D/'baseline.json').read_text())['installedSha256'],'cases':[],'mode':'baseline-longer-observation'}
def observe(name,wanted_height,seconds):
 before=req('streams').get('probeFrames',0);start=time.monotonic();line=[];lastkey=None
 while time.monotonic()-start<seconds:
  d=req('diagnostics');s=req('streams');m=d.get('media3',{});p=d.get('snapshot',{}).get('playback',{});sel=d.get('streamSelection') or {}
  row={'seconds':round(time.monotonic()-start,2),'state':m.get('switchState'),'media3':m.get('playbackState'),'playing':m.get('isPlaying'),'playWhenReady':m.get('playWhenReady'),'height':m.get('videoHeight'),'positionMs':m.get('currentPositionMs'),'frames':s.get('probeFrames',0),'activeId':s.get('activeStreamId'),'requestedId':sel.get('requestedStreamId'),'requestedQuality':sel.get('requestedQuality'),'requestedVoice':sel.get('requestedVoice'),'activeVoice':sel.get('activeVoice'),'fallbackReason':sel.get('fallbackReason'),'errorCode':m.get('playerErrorCode'),'errorCause':m.get('playerErrorCause'),'mediaId':str(m.get('mediaItemId')),'season':p.get('season'),'episode':p.get('episode'),'firstFrameMs':s.get('firstFrameLatencyMs')}
  key=tuple(row[k] for k in ['state','media3','playing','playWhenReady','height','activeId','fallbackReason','errorCode'])
  if key!=lastkey:line.append(row);lastkey=key
  if row['playing'] and row['state']=='READY' and row['frames']>before+4 and row['height']>0:
   row['passed']=row['height']==wanted_height if wanted_height else True;line.append(row);break
  if row['state']=='FAILED':row['passed']=False;break
  time.sleep(.4)
 else:row['passed']=False
 result={'case':name,'last':row,'timeline':line};out['cases'].append(result);print(json.dumps({'case':name,'last':row},ensure_ascii=False),flush=True);return row
try:
 snap=req('snapshot');assert snap['app']['uiAttached'] is False;out['initialCounts']=snap['library']['counts']
 act('player.probeSurface',{'enabled':True});act('player.stop')
 act('media.play',{'mediaId':'159','season':1,'episode':2,'streamId':'provider-item:v2:a03531516b353e38601ba3eb','quality':'Auto','voice':'Auto','persist':False,'recordHistory':False,'resume':False})
 initial=observe('240-start',240,40)
 if initial.get('passed'):
  act('player.seek',{'positionMs':30000});act('player.selectQuality',{'quality':'480p','persist':False});observe('480-at-30s',480,40)
  act('player.selectQuality',{'quality':'240p','persist':False});observe('240-return-at-30s',240,85)
  act('player.selectQuality',{'quality':'Auto','persist':False});observe('Auto',None,20)
finally:
 act('player.stop');act('player.probeSurface',{'enabled':False});out['countsPreserved']=req('snapshot')['library']['counts']==out.get('initialCounts')
 pid=subprocess.run(['rish','-c','pidof app.movia.android'],env=dict(os.environ,RISH_APPLICATION_ID='com.termux'),capture_output=True,text=True,timeout=10).stdout.strip().split()[0]
 p=subprocess.run(['rish','-c','logcat -d --pid='+pid+' -v brief -t 4000'],env=dict(os.environ,RISH_APPLICATION_ID='com.termux'),capture_output=True,text=True,timeout=12)
 lines=[]
 for x in (p.stdout+p.stderr).splitlines():
  if any(k in x for k in ['PlaybackSession','ExoPlayerImplInternal','InvalidResponse','HttpDataSource','SocketTimeout','SSLException']):
   x=re.sub(r'https?://[^\s\"<>]+','[redacted-locator]',x);x=re.sub(r'(?i)(cookie|authorization|token|password)\s*[:=]\s*\S+',r'\1=[redacted]',x);lines.append(x)
 (D/'baseline-player-log.txt').write_text('\n'.join(lines));out['sanitizedLogLines']=len(lines)
 (D/'baseline-reproduction.json').write_text(json.dumps(out,ensure_ascii=False,indent=2))
