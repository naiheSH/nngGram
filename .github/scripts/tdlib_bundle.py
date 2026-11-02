"""Export/verify only pinned Windows TDLib dependencies, never application or account data."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import struct
import subprocess
import zipfile

DLLS = {'tdjni.dll', 'libwinpthread-1.dll', 'libgcc_s_seh-1.dll', 'libstdc++-6.dll',
        'libcrypto-3-x64.dll', 'libssl-3-x64.dll', 'zlib1.dll'}
TOP_FILES = DLLS | {'tdlib.jar', 'manifest.properties', 'TDLib-LICENSE.txt', 'toolchain-versions.txt'}
LICENSE_PACKAGES = {'gcc-libs', 'libwinpthread', 'openssl', 'zlib', 'crt'}
BUILD_INPUTS = ('native/tdlib-source.properties', 'scripts/build-tdlib.ps1',
                'scripts/GenerateTdlibNativeMetadata.java', 'scripts/collect-windows-licenses.py', '.github/scripts/tdlib_bundle.py')


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def properties(text):
    result = {}
    for line in text.lstrip('\ufeff').splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        key, separator, value = line.partition('=')
        if not separator or key.strip() in result:
            raise ValueError('Invalid or duplicate bundle property')
        result[key.strip()] = value.strip()
    return result


def pin(root):
    commit = properties((root / 'native/tdlib-source.properties').read_text(encoding='utf-8-sig'))['commit']
    if not re.fullmatch('[0-9a-f]{40}', commit):
        raise ValueError('Invalid TDLib source pin')
    return commit


def run(*arguments):
    return subprocess.check_output([str(x) for x in arguments], text=True, encoding='utf-8', stderr=subprocess.STDOUT).strip()


def identity(root, msys, jdk):
    tool = msys / 'ucrt64/bin'
    value = {'format': 1, 'sourceCommit': pin(root), 'architecture': 'x64',
             'compiler': run(tool / 'g++.exe', '-dumpfullversion'),
             'compilerTarget': run(tool / 'g++.exe', '-dumpmachine'),
             'cmake': run(tool / 'cmake.exe', '--version').splitlines()[0],
             'packages': sorted(run(msys / 'usr/bin/pacman.exe', '-Q').splitlines()),
             'javac': run(jdk / 'bin/javac.exe', '-version'),
             'jdkRelease': digest(jdk / 'release'), 'compilerBinary': digest(tool / 'g++.exe'),
             'buildInputs': {name: digest(root / name) for name in BUILD_INPUTS}}
    if 'x86_64' not in value['compilerTarget']:
        raise ValueError('TDLib requires the x64 compiler')
    value['key'] = 'tdlib-windows-x64-v1-' + hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()
    return value


def regular_files(directory):
    result = {}
    for path in directory.rglob('*'):
        entry = path.lstat()
        if stat.S_ISLNK(entry.st_mode) or getattr(entry, 'st_file_attributes', 0) & 0x400:
            raise ValueError('Links and reparse points are forbidden in TDLib bundles')
        if path.is_file():
            result[path.relative_to(directory).as_posix()] = path
        elif not path.is_dir():
            raise ValueError('Nonregular bundle entry')
    return result


def allowed(name):
    if name in TOP_FILES:
        return True
    parts = name.split('/')
    return (len(parts) == 3 and parts[0] == 'licenses' and parts[1] in LICENSE_PACKAGES
            and re.fullmatch(r'(?:COPYING|LICENSE|README|COPYRIGHT)[A-Za-z0-9._-]*', parts[2], re.IGNORECASE))


def verify_pe(path):
    with path.open('rb') as source:
        header = source.read(64)
        if len(header) < 64 or header[:2] != b'MZ':
            raise ValueError('Invalid Windows library')
        offset = struct.unpack_from('<I', header, 60)[0]
        if offset > path.stat().st_size - 24:
            raise ValueError('Invalid PE header offset')
        source.seek(offset)
        header = source.read(24)
        if header[:4] != b'PE\0\0' or struct.unpack_from('<H', header, 4)[0] != 0x8664 or not struct.unpack_from('<H', header, 22)[0] & 0x2000:
            raise ValueError('Bundle library must be a Windows x64 DLL')


def validate_bundle(bundle, expected):
    files = regular_files(bundle)
    if not TOP_FILES.issubset(files) or not all(allowed(name) for name in files):
        raise ValueError('Bundle is incomplete or contains unexpected files')
    for package in LICENSE_PACKAGES:
        if not any(name.startswith(f'licenses/{package}/') for name in files):
            raise ValueError(f'Missing dependency license: {package}')
    if any(not 0 < path.stat().st_size <= 500_000_000 for path in files.values()):
        raise ValueError('Invalid dependency file size')
    manifest = properties(files['manifest.properties'].read_text(encoding='utf-8-sig'))
    required = {'format': '1', 'source.commit': expected['sourceCommit'], 'architecture': 'x64',
                'binding': 'official-java-jni', 'binding.loader.patch': 'absolute-path-v1',
                'compiler': expected['compiler'], 'cmake': expected['cmake']}
    if any(manifest.get(key) != value for key, value in required.items()):
        raise ValueError('TDLib manifest does not match the current source/toolchain')
    order = manifest.get('load.order', '').split(',')
    if len(order) != len(DLLS) or set(order) != DLLS or order[-1] != 'tdjni.dll':
        raise ValueError('Invalid complete DLL load order')
    hashes = {key[7:]: value for key, value in manifest.items() if key.startswith('sha256.')}
    if set(hashes) != DLLS | {'tdlib.jar'} or any(hashes[name] != digest(files[name]) for name in hashes):
        raise ValueError('TDLib manifest hash mismatch')
    for name in DLLS:
        verify_pe(files[name])
    with zipfile.ZipFile(files['tdlib.jar']) as jar:
        names = jar.namelist()
        if len(names) != len(set(names)) or not {'org/drinkless/tdlib/Client.class', 'org/drinkless/tdlib/TdApi.class'}.issubset(names):
            raise ValueError('Incomplete Java binding')
        provenance = properties(jar.read('META-INF/nnggram-tdlib.properties').decode('utf-8'))
        if (provenance.get('source.commit') != expected['sourceCommit'] or provenance.get('java.api.commit') != expected['sourceCommit']
                or provenance.get('loader.patch') != 'absolute-path-v1'):
            raise ValueError('Java/JNI binding source mismatch')
    packages = files['toolchain-versions.txt'].read_text(encoding='utf-8-sig').splitlines()
    if not packages or not set(packages).issubset(expected['packages']):
        raise ValueError('Bundle package versions do not match the active toolchain')
    return files


def export_bundle(source, cache, expected):
    # Copy only known dependencies: media, account/config files and build outputs cannot enter the cache.
    if cache.exists() and any(cache.iterdir()):
        raise ValueError('Cache staging destination must be empty')
    bundle = cache / 'bundle'
    bundle.mkdir(parents=True, exist_ok=True)
    candidates = regular_files(source)
    for name, path in candidates.items():
        if allowed(name):
            target = bundle / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
    files = validate_bundle(bundle, expected)
    inventory = {'format': 1, 'identity': expected, 'files': {name: digest(path) for name, path in sorted(files.items())}}
    (cache / 'bundle-inventory.json').write_text(json.dumps(inventory, sort_keys=True, indent=2) + '\n', encoding='utf-8')
    verify_cache(cache, expected)


def verify_cache(cache, expected):
    all_files = regular_files(cache)
    if 'bundle-inventory.json' not in all_files:
        raise ValueError('Missing complete bundle inventory')
    inventory = json.loads(all_files['bundle-inventory.json'].read_text(encoding='utf-8'))
    if inventory.get('format') != 1 or inventory.get('identity') != expected:
        raise ValueError('Cached dependency identity mismatch')
    files = validate_bundle(cache / 'bundle', expected)
    if set(all_files) != {'bundle-inventory.json'} | {'bundle/' + name for name in files}:
        raise ValueError('Unexpected cache content')
    if inventory.get('files') != {name: digest(path) for name, path in sorted(files.items())}:
        raise ValueError('Cached dependency inventory mismatch')
    return files


def restore(cache, destination, expected):
    files = verify_cache(cache, expected)
    if destination.exists() and any(destination.iterdir()):
        raise ValueError('TDLib restore destination must be empty')
    for name, path in files.items():
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
    validate_bundle(destination, expected)


def archive(cache, directory, expected):
    verify_cache(cache, expected)
    directory.mkdir(parents=True, exist_ok=True)
    output = directory / f"tdlib-windows-x64-{expected['sourceCommit']}-{expected['key'][-16:]}.zip"
    with zipfile.ZipFile(output, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive_file:
        for name, path in sorted(regular_files(cache).items()):
            archive_file.write(path, name)
    (directory / 'SHA256SUMS.txt').write_text(f'{digest(output)}  {output.name}\n', encoding='utf-8')
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['identity', 'export', 'verify', 'restore', 'archive'])
    parser.add_argument('--root', type=Path, default=Path.cwd())
    parser.add_argument('--identity', type=Path, required=True)
    parser.add_argument('--msys', type=Path)
    parser.add_argument('--jdk', type=Path)
    parser.add_argument('--cache', type=Path)
    parser.add_argument('--directory', type=Path)
    args = parser.parse_args()
    if args.command == 'identity':
        value = identity(args.root, args.msys, args.jdk)
        args.identity.write_text(json.dumps(value, sort_keys=True, indent=2) + '\n', encoding='utf-8')
        if os.environ.get('GITHUB_OUTPUT'):
            with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
                output.write(f"cache_key={value['key']}\ncommit={value['sourceCommit']}\nfingerprint={value['key'][-16:]}\n")
        print(value['key'])
        return
    expected = json.loads(args.identity.read_text(encoding='utf-8'))
    if args.command == 'export':
        export_bundle(args.directory, args.cache, expected)
    elif args.command == 'verify':
        verify_cache(args.cache, expected)
    elif args.command == 'restore':
        restore(args.cache, args.directory, expected)
    else:
        print(archive(args.cache, args.directory, expected).name)


if __name__ == '__main__':
    main()
