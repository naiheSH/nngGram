#!/usr/bin/env python3
"""Common Windows/macOS/Linux checks, JVM app packaging and offline acceptance."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[1]


def host_profile(system=None, machine=None):
    system = system or platform.system()
    machine = (machine or platform.machine()).lower()
    arch = {'amd64': 'x64', 'x86_64': 'x64', 'arm64': 'arm64', 'aarch64': 'arm64'}.get(machine)
    family = {'Windows': 'windows', 'Darwin': 'macos', 'Linux': 'linux'}.get(system)
    if not arch or not family or (family == 'windows' and arch != 'x64'):
        raise ValueError('Unsupported desktop host')
    return f'{family}-{arch}'


def run(*arguments):
    subprocess.run([str(arg) for arg in arguments], cwd=ROOT, check=True)


def gradle(*arguments):
    wrapper = ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    workers = int(os.environ.get('NNGGRAM_GRADLE_WORKERS', '2'))
    if workers not in range(1, 5):
        raise ValueError('NNGGRAM_GRADLE_WORKERS must be between 1 and 4')
    run(wrapper, *arguments, '--no-configuration-cache', '--console=plain', f'--max-workers={workers}')


def check():
    gradle(':desktopApp:assemble', ':desktopApp:test', ':shared:jvmTest', '-PnativeSmoke', '-PmediaSmoke')


def regression_check():
    # Run the prior cross-platform failures first; the following full check still
    # executes every test. A regression is visible before a long UI suite finishes.
    gradle(':shared:jvmTest', '-PmediaSmoke', '--tests', '*ConversationJumpHighlightRegressionRenderTest',
           '--tests', '*ComposePixelSceneClockTest', '--tests', '*ProfileStickerAvatarCodecSmokeTest',
           '--tests', '*ProfileStickerAvatarArtworkOwnershipTest')


def executable(app, profile):
    if profile.startswith('macos-'):
        return app / 'Contents/MacOS/nngGram'
    if profile.startswith('windows-'):
        return app / 'nngGram.exe'
    return next((p for p in (app / 'bin').glob('*') if p.name.lower() == 'nnggram'), app / 'bin/nngGram')


def verify_app(app, profile, reports, label):
    run(executable(app, profile), '--tdlib-native-smoke', reports / f'{label}-tdlib.txt')
    run(executable(app, profile), '--platform-native-smoke', reports / f'{label}-platform.json')
    if profile.startswith('macos-'):
        run('codesign', '--verify', '--deep', '--strict', app)


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def build(args):
    profile = host_profile()
    if not (ROOT / 'native' / profile / 'manifest.properties').is_file():
        raise RuntimeError('Prepare the host TDLib bundle first; see README.md and scripts/build-tdlib.*')
    if not args.skip_tests:
        check()
    mac = profile.startswith('macos-')
    image_task = ':desktopApp:finalizeMacosApp' if mac else ':desktopApp:createDistributable'
    tasks = [image_task]
    if args.installer:
        tasks.append(':desktopApp:package' + ('Dmg' if mac else 'Msi' if os.name == 'nt' else 'Deb'))
    if args.release:
        tasks.append('-PrequireTelegramApi=true')
    gradle(*tasks)
    binaries = ROOT / 'desktopApp/build/compose/binaries/main'
    app = binaries / 'app' / ('nngGram.app' if mac else 'nngGram')
    if not app.is_dir() and profile.startswith('linux-'):
        app = binaries / 'app/nnggram'
    output = ROOT / 'build/distributions' / profile
    output.mkdir(parents=True, exist_ok=True)
    reports = output / ('verification-' + uuid.uuid4().hex)
    reports.mkdir()
    verify_app(app, profile, reports, 'app')
    run(executable(app, profile), '--desktop-main-render', reports / 'main.png')
    stem = output / f'nngGram-{profile}'
    artifacts = []
    if mac:
        archive = stem.with_suffix('.zip')
        if archive.exists():
            archive.unlink()
        run('ditto', '-c', '-k', '--sequesterRsrc', '--keepParent', app, archive)
    else:
        archive = Path(shutil.make_archive(str(stem), 'zip' if os.name == 'nt' else 'gztar', app.parent, app.name))
    artifacts.append(archive)
    if args.installer:
        extension = 'dmg' if mac else 'msi' if os.name == 'nt' else 'deb'
        installers = list((binaries / extension).glob('*.' + extension))
        if not installers:
            raise RuntimeError('Installer task produced no installer')
        for source in installers:
            target = output / source.name
            shutil.copy2(source, target)
            artifacts.append(target)
            if mac:
                # Nucleus removes its staging copy. Inspect the actual read-only DMG instead.
                with tempfile.TemporaryDirectory(prefix='mount-', dir=reports) as mount:
                    run('hdiutil', 'attach', '-readonly', '-nobrowse', '-mountpoint', mount, target)
                    try:
                        verify_app(Path(mount) / 'nngGram.app', profile, reports, 'dmg')
                    finally:
                        run('hdiutil', 'detach', mount)
    metadata = {
        'platform': profile, 'runtime': 'bundled-jvm', 'builderIdentityRequired': args.release,
        'offlineNativeVerified': True, 'realAccountTested': False,
        'testsExecutedDuringBuild': not args.skip_tests,
        'artifacts': [{'name': p.name, 'bytes': p.stat().st_size, 'sha256': digest(p)} for p in artifacts],
        'verificationDirectory': reports.name,
    }
    if mac:
        import plistlib
        metadata['minimumMacOSVersion'] = plistlib.loads((app / 'Contents/Info.plist').read_bytes())['LSMinimumSystemVersion']
    (output / 'build-info.json').write_text(json.dumps(metadata, indent=2) + '\n')
    (output / 'SHA256SUMS.txt').write_text(''.join(f"{p['sha256']}  {p['name']}\n" for p in metadata['artifacts']))
    print('Desktop artifacts:', output, flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['check', 'regression-check', 'build'])
    parser.add_argument('--full-tests', action='store_true', help='Compatibility flag: the full regression suite is now the default')
    parser.add_argument('--skip-tests', action='store_true')
    parser.add_argument('--release', action='store_true', help='Require builder-owned Telegram identity')
    parser.add_argument('--installer', action='store_true', help='Also generate DMG/MSI/DEB')
    args = parser.parse_args()
    if args.command == 'check':
        check()
    elif args.command == 'regression-check':
        regression_check()
    else:
        build(args)


if __name__ == '__main__':
    main()
