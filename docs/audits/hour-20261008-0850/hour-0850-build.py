from pathlib import Path
import subprocess,os,json,time
out=Path.home()/'.cache/movia-architecture-20261002';repo=out/'repo'
start=time.monotonic()
env=dict(os.environ,JAVA_HOME='/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk')
with (out/'hour-0850-android-build.txt').open('w') as log:
    code=subprocess.run(['./gradlew','--offline','--no-daemon',':app:testDebugUnitTest',':app:assembleDebug',':app:compileDebugAndroidTestKotlin'],cwd=repo,env=env,stdout=log,stderr=subprocess.STDOUT).returncode
result={'exitCode':code,'seconds':round(time.monotonic()-start,2)}
(out/'hour-0850-android-build-result.json').write_text(json.dumps(result))
print(json.dumps(result))
raise SystemExit(code)
