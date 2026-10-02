from pathlib import Path
import json,sqlite3,subprocess,datetime,shutil,re
out=Path('/data/data/com.termux/files/home/.cache/movia-audit-500-20261002')
repo=out/'repo';audit=repo/'docs/audits/films-500-20261002'
stats={'infrastructureOnlyRows':0,'excludedTvRows':0,'realStartupObservations':0,'decodedMovies':0}
db=sqlite3.connect('file:/data/data/com.termux/files/home/projects/media-parser/catalog.db?mode=ro',uri=True)
for f in (audit/'results').glob('*.json'):
 r=json.loads(f.read_text())
 kind=db.execute('select media_type,seasons_count,episodes_count from movies where id=?',(int(r['mediaId']),)).fetchone()
 infra=not r.get('startup')
 tv=kind and (kind[0]!='movie' or (kind[1] or 0)>0 or (kind[2] or 0)>0)
 if infra or tv:
  d=audit/('infrastructure-failures' if infra else 'excluded-tv');d.mkdir(exist_ok=True);shutil.move(str(f),d/f.name)
  stats['infrastructureOnlyRows' if infra else 'excludedTvRows']+=1
 else:
  stats['realStartupObservations']+=1;stats['decodedMovies']+=bool(r['startup'].get('decoded'))
  if r.get('inventory'):
   voices=r['inventory']['voiceLabels'];r['inventory']['technicalTrackLabels']=voices
   r['inventory']['voiceLabels']=[v for v in voices if not re.fullmatch(r'(?:rus|fre|eng|def|ukr|und)\d+(?: · \d+)?|default(?: · \d+)?|delete',v,re.I)]
   f.write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n')
# Prioritize known cached HTTP choices; selection remains independent of decoder outcome.
old=json.loads((out/'manifest.json').read_text())
exclusions=set(json.loads((audit/'manifest.json').read_text())['excludedTitles'])
eligible=json.loads((out/'http-eligible.json').read_text())
eligible.sort(key=lambda x:(-min(x['cachedHttpVoices'],3),-x['voteCount'],int(x['mediaId'])))
pool=eligible+old;manifest=[];seen=set()
for m in pool:
 if m['mediaId'] in seen or m['title'] in exclusions:continue
 row=db.execute('select id,title,year,media_type,seasons_count,episodes_count,tmdb_id,imdb_id,original_title,streams from movies where id=?',(int(m['mediaId']),)).fetchone()
 if not row or row[3]!='movie' or (row[4] or 0)>0 or (row[5] or 0)>0:continue
 seen.add(m['mediaId']);manifest.append(dict(m,index=len(manifest)+1,tmdbId=row[6],imdbId=row[7],originalTitle=row[8]))
 if len(manifest)==500:break
assert len(manifest)==500
shutil.copyfile(out/'manifest.json',out/'original-random-manifest.json')
(out/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False))
(audit/'prioritized-manifest.json').write_text(json.dumps({'selected':500,'selection':'Known cached HTTP choices first, then deterministic random cohort. Selection by cached availability before decoding, not by successful playback. TV rows excluded using refreshed metadata. Original manifest retained.','movies':[{k:v for k,v in m.items() if k!='imdbId'} for m in manifest]},ensure_ascii=False,indent=2)+'\n')
(audit/'counter-correction.json').write_text(json.dumps(stats,indent=2)+'\n')
s=json.loads((audit/'summary.json').read_text());s.update(attempted=stats['realStartupObservations'],decodedStarted=stats['decodedMovies'],withThreeVoiceLabels=None,infrastructureFailuresExcluded=stats['infrastructureOnlyRows'],excludedTv=stats['excludedTvRows'],status='PAUSED_FOR_FOREGROUND_FIX')
(audit/'summary.json').write_text(json.dumps(s,indent=2)+'\n')
log=audit/'actions.jsonl'
with log.open('a') as f:f.write(json.dumps({'seq':sum(1 for _ in log.open())+1,'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'action':'counter_correction','result':stats,'reason':'Process killed by Android excessive background CPU. Connection failures are not movie checks; technical duplicate voice labels are not extra studios.'})+'\n')
subprocess.run(['git','add','docs/audits/films-500-20261002'],cwd=repo,check=True,capture_output=True)
subprocess.run(['git','commit','-m','Correct audit counters and preserve infrastructure failures separately'],cwd=repo,check=True,capture_output=True)
subprocess.run(['git','push','origin','HEAD:refs/heads/movia-audit-500-20261002'],cwd=repo,check=True,capture_output=True,timeout=55)
# Runtime harness fixes.
p=out/'runner.py';s=p.read_text().replace('signal,re,sys','signal,re,sys,fcntl')
s=s.replace("def checkpoint(event,extra=None):","def checkpoint(event,extra=None):\n with (OUT/'git-journal.lock').open('a') as lock:\n  fcntl.flock(lock,fcntl.LOCK_EX)\n  return checkpoint_locked(event,extra)\ndef checkpoint_locked(event,extra=None):")
s=s.replace("  SEQ+=1;event=", "  SEQ=sum(1 for _ in (AUDIT/'actions.jsonl').open())+1;event=")
s=s.replace(" started=time.monotonic();last=None"," started=time.monotonic();last=None;last_streams={}")
s=s.replace("s,m,o=observations();last=o","s,m,o=observations();last=o;last_streams=s")
s=s.replace(" except (RuntimeError,TimeoutError,urllib.error.URLError):pass"," except urllib.error.URLError as e:\n   if getattr(e,'reason',None) and ('refused' in str(e).lower() or 'reset' in str(e).lower()):raise\n  except (RuntimeError,TimeoutError):pass")
s=s.replace("'bounded frame observation timeout'},{}","'bounded frame observation timeout'},last_streams")
a=s.index('def inventory(streams):');b=s.index('def read_results():',a)
s=s[:a]+'''def inventory(streams):
 pairs=set();voices=set();qualities=set();source_rows=0;automatic_voices=set();technical=set()
 def meaningful(v):
  return bool(v) and v.casefold() not in ['auto','не указано','unknown','none','delete','default'] and not re.fullmatch(r'(?:rus|fre|eng|def|ukr|und)\\d+(?: · \\d+)?|default(?: · \\d+)?',v,re.I)
 for group in streams.get('qualities',[]):
  q=group.get('quality','')
  for row in group.get('voices',[]):
   source_rows+=1;v=str(row.get('voice') or '').strip()
   if meaningful(v):voices.add(v)
   elif v:technical.add(v)
   if re.fullmatch(r'\\d{3,4}p',q):
    qualities.add(q)
    if meaningful(v):pairs.add((q,v))
   elif q.lower() in ('auto','не указано') and meaningful(v):automatic_voices.add(v)
 for x in streams.get('videoTracks',[]):
  if x.get('height',0)>0:qualities.add(str(x['height'])+'p')
 for x in streams.get('audioTracks',[]):
  v=str(x.get('voice') or '').strip()
  if meaningful(v):voices.add(re.sub(r' · \\d+$','',v))
  elif v:technical.add(v)
 for q in qualities:
  for v in automatic_voices:pairs.add((q,v))
 return {'voiceLabels':sorted(voices),'technicalTrackLabels':sorted(technical),'qualityLabels':sorted(qualities,key=lambda x:int(x[:-1])),'advertisedPairs':[list(x) for x in sorted(pairs)],'sourceRows':source_rows,'inventoryComplete':False}
'''+s[b:]
s=s.replace("versionCode']==319","versionCode']==320").replace("'build']=319","'build']=320")
s=s.replace("checkpoint({'action':'headless_cold_launch319','result':json.loads(Path('/data/data/com.termux/files/home/.cache/movia-handover-319-20261002/cold-headless.json').read_text())})","checkpoint({'action':'headless_foreground_launch320','note':'No Activity. Foreground service retains existing registry session during user-requested audit.'})")
s=s.replace("or (o.get('selectedAudioLabel'),o.get('selectedAudioLanguage'))", "or (o.get('selectedAudioLabel'),o.get('selectedAudioLanguage'))")
s=s.replace("(o.get('selectedAudio')!=prior.get('selectedAudio') or (o.get('selectedAudioLabel'),o.get('selectedAudioLanguage'))!=(prior.get('selectedAudioLabel'),prior.get('selectedAudioLanguage')))","((o.get('selectedAudioLabel'),o.get('selectedAudioLanguage'))!=(prior.get('selectedAudioLabel'),prior.get('selectedAudioLanguage')))")
s=s.replace("row['error']=str(e)[:150];save_movie(row)","checkpoint({'action':'audit_infrastructure_pause','mediaId':media_id,'error':str(e)[:150]});PAUSE.touch();break")
s=s.replace("completed=SUMMARY['attempted']==500","completed=SUMMARY['attempted']>=500")
p.write_text(s);shutil.copyfile(p,audit/'runner.py')
print(json.dumps(stats))
