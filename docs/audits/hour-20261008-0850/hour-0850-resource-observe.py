"""Read-only observations during the decoder audit; not a controlled benchmark."""
from pathlib import Path
import subprocess,os,time,json,re,datetime
C=Path.home()/'.cache/movia-architecture-20261002';D=C/'repo/docs/audits/hour-20261008-0850'
env=dict(os.environ,RISH_APPLICATION_ID='com.termux')
out={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'samples':[],
 'limitations':'Concurrent mass-audit observations only. No cold/warm comparative benchmark, no CPU peak/queue/cancellation gate, no <=5s claim.'}
for i in range(6):
    s={'at':datetime.datetime.now(datetime.timezone.utc).isoformat()}
    for label,cmd in [('memory','dumpsys meminfo -s app.movia.android'),('cpu','dumpsys cpuinfo')]:
        try:
            p=subprocess.run(['rish','-c',cmd],env=env,capture_output=True,text=True,timeout=12)
            s[label+'ReadExitCode']=p.returncode
            if label=='memory':
                lines=[x.strip() for x in p.stdout.splitlines() if 'TOTAL PSS:' in x or 'TOTAL RSS:' in x]
                s['memoryTotals']=lines
                for key,pattern in [('totalPssKb',r'TOTAL PSS:\s*(\d+)'),('totalRssKb',r'TOTAL RSS:\s*(\d+)')]:
                    m=re.search(pattern,p.stdout)
                    if m:s[key]=int(m.group(1))
            else:s['appCpuLines']=[x.strip() for x in p.stdout.splitlines() if 'app.movia.android' in x][:3]
        except Exception as e:s[label+'ErrorClass']=type(e).__name__
    out['samples'].append(s)
    (D/'resource-observation.json').write_text(json.dumps(out,indent=2))
    if i<5:time.sleep(15)
print(json.dumps({'samples':len(out['samples']),'pssKb':[x.get('totalPssKb') for x in out['samples']],'rssKb':[x.get('totalRssKb') for x in out['samples']]}),flush=True)
