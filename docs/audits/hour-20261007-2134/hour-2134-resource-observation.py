"""Passive /proc samples during the decoder audit; not a controlled benchmark."""
from pathlib import Path
import os,json,subprocess,time,datetime,re,signal
C=Path.home()/'.cache/movia-architecture-20261002';D=C/'repo/docs/audits/hour-20261007-2134'
END=datetime.datetime(2026,10,7,22,27,tzinfo=datetime.timezone.utc).timestamp();STOP=False
samples=[];previous=None
hz=os.sysconf('SC_CLK_TCK');page=os.sysconf('SC_PAGE_SIZE')
def stop(*args):
 global STOP
 STOP=True
signal.signal(signal.SIGTERM,stop);signal.signal(signal.SIGINT,stop)
def save(final=False):
 out={'installedApkSha256':json.loads((C/'hour-2134-install-cold.json').read_text())['installedSha256'],'samples':samples,'sampleIntervalSeconds':45,'clockTicksPerSecond':hz,'pageSize':page,'final':final,'rssPeakKiB':max((s.get('rssKiB',0) for s in samples),default=0),'cpuPeakOneCorePercent':max((s.get('cpuOneCorePercent') or 0 for s in samples),default=0),'limitations':'Passive app /proc RSS/CPU during audit and active background services, not PSS or a controlled cold/warm benchmark. No general startup <=5s claim.'}
 (D/'resource-observation.json').write_text(json.dumps(out,indent=2))
try:
 while not STOP and time.time()<END:
  now=time.monotonic();p=subprocess.run(['rish','-c','movia_resource_pid=$(pidof app.movia.android); cat /proc/${movia_resource_pid%% *}/stat /proc/${movia_resource_pid%% *}/statm'],env=dict(os.environ,RISH_APPLICATION_ID='com.termux'),capture_output=True,text=True,timeout=12)
  text=p.stdout+'\n'+p.stderr;sample={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'exitCode':p.returncode}
  stat=next((x for x in text.splitlines() if re.match(r'^\d+ \(.+\) [A-Z] ',x)),None)
  if stat:
   pid=int(stat.split()[0]);parts=stat[stat.rfind(')')+2:].split();ticks=int(parts[11])+int(parts[12]);rss=int(parts[21])*page/1024
   sample.update(pid=pid,rssKiB=round(rss,1),threadCount=int(parts[17]),cpuOneCorePercent=None)
   if previous and previous[0]==pid:sample['cpuOneCorePercent']=round((ticks-previous[2])/hz/(now-previous[1])*100,2)
   previous=(pid,now,ticks)
  else:sample['error']='proc_stat_unavailable'
  samples.append(sample);save();print(json.dumps(sample),flush=True)
  for _ in range(45):
   if STOP or time.time()>=END:break
   time.sleep(1)
finally:save(True)
