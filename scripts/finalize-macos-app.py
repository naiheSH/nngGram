#!/usr/bin/env python3
"""Refresh native hashes after Nucleus patches/signs Mach-O files, then sign the app."""
import hashlib
from pathlib import Path
import subprocess
import sys
import plistlib
import re
import os

app = Path(sys.argv[1]).resolve()
resources = app / 'Contents/app/resources'
manifest = resources / 'manifest.properties'
lines = manifest.read_text().splitlines()
updated = []
for line in lines:
    if line.startswith('sha256.'):
        key, _ = line.split('=', 1)
        name = key.removeprefix('sha256.')
        if Path(name).name != name:
            raise ValueError('Manifest file must be a sibling library or binding')
        with (resources / name).open('rb') as stream:
            digest = hashlib.sha256()
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(chunk)
            line = key + '=' + digest.hexdigest()
    updated.append(line)
# Do not advertise an older OS than the host-built native dependencies actually support.
limit = os.environ.get('NNGGRAM_MACOS_MAX_VERSION')
minimum = tuple(int(part) for part in limit.split('.')) if limit else (11, 0)
requirements = []
for binary in [*resources.rglob('*.dylib'), resources / 'media/ffmpeg', resources / 'media/ffprobe']:
    if binary.is_file():
        info = subprocess.check_output(['vtool', '-show-build', str(binary)], text=True)
        for version in re.findall(r'\bminos\s+([0-9.]+)', info):
            required = tuple(int(part) for part in version.split('.'))
            requirements.append((required, binary))
            minimum = max(minimum, required)
if limit:
    maximum = tuple(int(part) for part in limit.split('.'))
    incompatible = [f"{binary.name}: {'.'.join(map(str, required))}" for required, binary in requirements
                    if (required + (0,) * 3)[:3] > (maximum + (0,) * 3)[:3]]
    if incompatible:
        raise SystemExit(f"Native dependencies exceed macOS {limit}: " + ', '.join(incompatible))
manifest.write_text('\n'.join(updated) + '\n')
plist = app / 'Contents/Info.plist'
with plist.open('rb') as stream:
    info = plistlib.load(stream)
info['LSMinimumSystemVersion'] = '.'.join(str(part) for part in minimum)
with plist.open('wb') as stream:
    plistlib.dump(info, stream)
subprocess.run(['codesign', '--force', '--sign', '-', str(app)], check=True)
subprocess.run(['codesign', '--verify', '--deep', '--strict', str(app)], check=True)
print('Finalized macOS native manifest and ad-hoc signature:', app)
