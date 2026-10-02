from pathlib import Path
import subprocess, os, json, hashlib, re, shutil, xml.etree.ElementTree as ET, time, sys, urllib.request
sys.path.insert(0, '/data/data/com.termux/files/home/.cache/movia-architecture-20261002')
from journal import ROOT, OUT, record

result = {'versionCode': 322, 'versionName': '0.0.1', 'steps': [], 'uiInteraction': False}
def save(): (OUT/'verification322.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
def shell(name, command, timeout=70):
    p = subprocess.run(['rish','-c',command], stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                       timeout=timeout, env=dict(os.environ, RISH_APPLICATION_ID='com.termux'))
    text = p.stdout.decode(errors='replace') + p.stderr.decode(errors='replace')
    (OUT/(name+'-322.log')).write_text(text)
    result['steps'].append({'name': name, 'exitCode': p.returncode}); save()
    print(name, 'exit', p.returncode, flush=True)
    if p.returncode: raise RuntimeError(name+': '+text[-1800:])
    return text
def snapshot():
    p = subprocess.run(['/data/data/com.termux/files/home/bin/movia-agent','snapshot'], capture_output=True, timeout=12)
    return json.loads(p.stdout)

fixture = None
try:
    assert 'BUILD SUCCESSFUL' in (OUT/'android-322-build.txt').read_text(errors='replace')
    before = json.loads((OUT/'user-before-322-private.json').read_text())
    assert before['app']['uiAttached'] is False, 'Unexpected attached phone UI'
    stage = Path('/sdcard/Download/Movia_QA_322'); stage.mkdir(parents=True, exist_ok=True)
    for name, source in [('app.apk', ROOT/'app/build/outputs/apk/debug/app-debug.apk'),
                         ('test.apk', ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]:
        shutil.copyfile(source, stage/name)
        result[name] = {'bytes': source.stat().st_size, 'sha256': hashlib.sha256(source.read_bytes()).hexdigest()}
    text = shell('install', 'cp /sdcard/Download/Movia_QA_322/app.apk /data/local/tmp/movia-322-app.apk && cp /sdcard/Download/Movia_QA_322/test.apk /data/local/tmp/movia-322-test.apk && pm install -r -d -t /data/local/tmp/movia-322-app.apk && pm install -r -d -t /data/local/tmp/movia-322-test.apk', 110)
    assert len(re.findall(r'^Success\s*$', text, re.M)) == 2
    text = shell('package', 'dumpsys package app.movia.android')
    assert re.search(r'versionCode=322\b', text)
    text = shell('installed-apk', 'APK_PATH=$(pm path app.movia.android | head -n 1 | cut -d: -f2); sha256sum "$APK_PATH"')
    assert result['app.apk']['sha256'] in text
    subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(OUT/'fixture-key.pem'),'-out',str(OUT/'fixture-cert.pem'),'-days','1','-subj','/CN=invalid.example'], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20)
    os.chmod(OUT/'fixture-key.pem', 0o600)
    fixture = subprocess.Popen(['python',str(OUT/'fixture322.py')], stdout=(OUT/'fixture-322.log').open('w'), stderr=subprocess.STDOUT)
    ready = False
    for _ in range(25):
        if fixture.poll() is not None: raise RuntimeError('Fixture process exited')
        try:
            with urllib.request.urlopen('http://127.0.0.1:8897/__stats', timeout=1) as response: json.load(response)
            ready = True; break
        except OSError: time.sleep(.1)
    assert ready, 'Fixture did not start'
    classes = ['app.movia.android.domain.legacy.LegacyEngineRegressionTest',
               'app.movia.android.ui.MoviaInteractionMotionRegressionTest',
               'app.movia.android.ui.player.AdaptivePlaybackRegressionTest']
    counts = {}
    for name in classes:
        source = ROOT/'app/src/androidTest/java'/Path(*name.split('.')).with_suffix('.kt')
        counts[name] = len(re.findall(r'@Test\s+fun\b', source.read_text()))
    text = shell('native-tests', 'am instrument -w -r -e class '+','.join(classes)+' app.movia.android.test/androidx.test.runner.AndroidJUnitRunner', 360)
    match = re.search(r'OK \((\d+) tests?\)', text)
    assert match and int(match[1]) == sum(counts.values()) and 'FAILURES!!!' not in text and 'INSTRUMENTATION_FAILED' not in text, text[-7500:]
    result['nativeTests'] = {'passed': int(match[1]), 'failed': 0, 'expectedByClass': counts}
    result['unitTests'] = {'tests': 0, 'failures': 0, 'errors': 0}
    for file in (ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
        element = ET.parse(file).getroot()
        for key in result['unitTests']: result['unitTests'][key] += int(element.get(key,0))
    assert result['unitTests']['tests'] >= 151 and not result['unitTests']['failures'] and not result['unitTests']['errors']
    shell('cold-force-stop', 'am force-stop app.movia.android')
    start = time.monotonic()
    text = shell('foreground-start', 'am start-foreground-service -n app.movia.android/.agent.AgentAuditService')
    assert 'Error' not in text and 'Exception' not in text, text
    after = None
    for _ in range(30):
        try:
            after = snapshot()
            if after.get('initialization',{}).get('ready'): break
        except Exception: pass
        time.sleep(.15)
    assert after and after['app']['versionCode'] == 322 and after['app']['uiAttached'] is False
    result['coldHeadlessReadyMs'] = round((time.monotonic()-start)*1000)
    (OUT/'cold-snapshot-322-private.json').write_text(json.dumps(after, ensure_ascii=False))
    text = shell('foreground-state', 'dumpsys activity services app.movia.android')
    assert 'AgentAuditService' in text and 'isForeground=true' in text
    result['foregroundServiceConfirmed'] = True
    result['userLibraryPreserved'] = before['library'] == after['library']
    result['settingsPreserved'] = before['settings'] == after['settings']
    assert result['userLibraryPreserved'] and result['settingsPreserved'], 'User state changed'
    pid = shell('pid', 'pidof app.movia.android').strip().split()[0]
    result['pid'] = pid
    text = shell('cold-process-log', 'logcat -d --pid='+pid, 25)
    result['fatalExceptionSeen'] = 'FATAL EXCEPTION' in text
    assert not result['fatalExceptionSeen']
    baseline = json.loads((OUT/'baseline.json').read_text())
    result['rootIndexPreserved'] = hashlib.sha256((ROOT/'.git/index').read_bytes()).hexdigest() == baseline['indexSha256']
    assert result['rootIndexPreserved']
    result['status'] = 'BUILD_INSTALL_COLD_HEADLESS_NATIVE_PASS'; save()
except Exception as error:
    result['status'] = 'FAIL'; result['error'] = str(error)[-2500:]; save(); raise
finally:
    if fixture:
        fixture.terminate()
        try: fixture.wait(5)
        except subprocess.TimeoutExpired: fixture.kill(); fixture.wait(5)
    artifacts = [(OUT/'verification322.json','build322-verification.json')]
    native_log = OUT/'native-tests-322.log'
    if native_log.exists(): artifacts.append((native_log,'build322-native-tests.log'))
    print(json.dumps(record('build322_headless_native_verification', result, artifacts=artifacts), ensure_ascii=False), flush=True)
    print(json.dumps(result, ensure_ascii=False), flush=True)
