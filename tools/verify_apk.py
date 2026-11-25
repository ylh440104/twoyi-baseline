#!/usr/bin/env python3
"""Check CI's debug APK packaging without claiming device/runtime compatibility."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
apks = list((root / 'app/build/outputs/apk/debug').glob('*.apk'))
if len(apks) != 1:
    raise SystemExit(f'Expected one debug APK, found {len(apks)}')
apk = apks[0]
expected = {
    'lib/arm64-v8a/libtwoyi.so': root / 'app/src/main/jniLibs/arm64-v8a/libtwoyi.so',
    'lib/arm64-v8a/libloader.so': root / 'app/src/main/jniLibs/arm64-v8a/libloader.so',
    'lib/arm64-v8a/libadb.so': root / 'app/src/main/jniLibs/arm64-v8a/libadb.so',
    'lib/arm64-v8a/libOpenglRender.so': root / 'app/src/main/jniLibs/arm64-v8a/libOpenglRender.so',
    'assets/rootfs.7z': root / 'app/src/main/assets/rootfs.7z',
    'assets/rom.ini': root / 'app/src/main/assets/rom.ini',
}

def digest(stream):
    value = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b''):
        value.update(block)
    return value.hexdigest()

def revision(directory):
    sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=directory, text=True).strip()
    if not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise SystemExit(f'Unexpected Git revision in {directory}')
    return sha

report = {'apk': apk.name, 'checks': {}, 'scope': 'packaging only; no device execution'}
if os.environ.get('GITHUB_ACTIONS') == 'true':
    overlay = revision(root.parent / 'overlay')
    if overlay != os.environ['GITHUB_SHA']:
        raise SystemExit('Development checkout does not match the Actions revision')
    report['source'] = {
        'upstream_sha': revision(root),
        'development_sha': overlay,
        'repository': os.environ['GITHUB_REPOSITORY'],
        'run_id': os.environ['GITHUB_RUN_ID'],
        'run_attempt': os.environ['GITHUB_RUN_ATTEMPT'],
    }
with zipfile.ZipFile(apk) as archive:
    if any(name in archive.namelist() for name in (
            'assets/guest-import-fixture.apk', 'assets/guest-import-split.apk')):
        raise SystemExit('Guest import fixture belongs only in the instrumentation APK')
    for name in ['AndroidManifest.xml', 'classes.dex', 'lib/arm64-v8a/libp7zip.so', *expected]:
        if archive.getinfo(name).file_size == 0:
            raise SystemExit(f'Empty required APK entry: {name}')
    for name, source in expected.items():
        with archive.open(name) as packaged, source.open('rb') as original:
            actual, wanted = digest(packaged), digest(original)
        # AGP may strip native debug symbols; assets must remain byte-identical.
        if name.startswith('assets/') and actual != wanted:
            raise SystemExit(f'Packaged bytes do not match build input: {name}')
        report['checks'][name] = {'packaged_sha256': actual, 'input_sha256': wanted}
with apk.open('rb') as stream:
    report['sha256'] = digest(stream)
report['bytes'] = apk.stat().st_size
if 'source' in report:
    test_apks = list((root / 'app/build/outputs/apk/androidTest/debug').glob('*.apk'))
    if len(test_apks) != 1:
        raise SystemExit(f'Expected one instrumentation APK, found {len(test_apks)}')
    test_apk = test_apks[0]
    with test_apk.open('rb') as stream:
        report['instrumentation'] = {'apk': test_apk.name, 'sha256': digest(stream)}
    with zipfile.ZipFile(test_apk) as archive:
        for filename, report_key in (
                ('guest-import-fixture.apk', 'guest_import_fixture_sha256'),
                ('guest-import-split.apk', 'guest_import_split_sha256')):
            fixture = root / 'app/src/androidTest/assets' / filename
            with archive.open(f'assets/{filename}') as packaged, fixture.open('rb') as original:
                fixture_sha = digest(packaged)
                if fixture_sha != digest(original):
                    raise SystemExit(f'Packaged {filename} does not match the signed APK')
            report['instrumentation'][report_key] = fixture_sha
output = root / 'app/build/reports/apk-packaging.json'
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(report, indent=2) + '\n')
print(output.read_text())
