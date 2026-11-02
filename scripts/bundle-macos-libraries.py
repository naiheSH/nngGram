#!/usr/bin/env python3
"""Copy non-system Mach-O dependencies beside a binary and make them relocatable."""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess


def run(*args):
    return subprocess.check_output(args, text=True)


def bundle(binary: Path):
    directory = binary.parent
    visited = set()
    order = []

    def copy_licenses(source):
        parts = source.resolve().parts
        if 'Cellar' not in parts:
            return
        index = parts.index('Cellar')
        formula = Path(*parts[:index + 3])
        destination = directory / 'licenses' / parts[index + 1]
        for item in formula.iterdir():
            if item.name.upper().startswith(('LICENSE', 'COPYING', 'NOTICE', 'AUTHORS')):
                destination.mkdir(parents=True, exist_ok=True)
                if item.is_file():
                    shutil.copy2(item, destination / item.name)
                elif item.is_dir():
                    shutil.copytree(item, destination / item.name, dirs_exist_ok=True)

    def collect(path):
        if path.name in visited:
            return
        visited.add(path.name)
        path.chmod(path.stat().st_mode | 0o200)
        lines = run('otool', '-L', str(path)).splitlines()[1:]
        for line in lines:
            dependency = line.strip().split(' (compatibility version')[0]
            if dependency.startswith(('/usr/lib/', '/System/Library/')):
                continue
            name = Path(dependency).name
            if name == path.name:
                continue  # The dylib's own install ID.
            target = directory / name
            if dependency.startswith('@loader_path/'):
                source = path.parent / dependency.removeprefix('@loader_path/')
            elif dependency.startswith('/'):
                source = Path(dependency)
            elif target.is_file():
                source = target
            else:
                raise RuntimeError(f'Unresolved dependency in {path.name}: {dependency}')
            if name not in visited and source.resolve() != target.resolve():
                shutil.copy2(source, target)
            copy_licenses(source)
            collect(target)
            run('install_name_tool', '-change', dependency, '@loader_path/' + name, str(path))
        if path.suffix == '.dylib':
            run('install_name_tool', '-id', '@loader_path/' + path.name, str(path))
        # Remove build-machine JDK/Homebrew rpaths; all dependencies are now relative.
        commands = run('otool', '-l', str(path)).splitlines()
        for index, line in enumerate(commands):
            if line.strip() == 'cmd LC_RPATH':
                rpath = commands[index + 2].strip().removeprefix('path ').split(' (offset')[0]
                if rpath.startswith('/'):
                    run('install_name_tool', '-delete_rpath', rpath, str(path))
        run('codesign', '--force', '--sign', '-', str(path))
        order.append(path.name)

    collect(binary)
    return order


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('binary', type=Path)
    parser.add_argument('--tdlib-manifest', action='store_true')
    args = parser.parse_args()
    order = bundle(args.binary.resolve())
    if args.tdlib_manifest:
        manifest = args.binary.parent / 'manifest.properties'
        lines = [line for line in manifest.read_text().splitlines()
                 if not line.startswith(('load.order=', 'sha256.'))]
        lines.append('load.order=' + ','.join(order))
        for name in ['tdlib.jar', *order]:
            with (manifest.parent / name).open('rb') as stream:
                digest = hashlib.sha256()
                for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                    digest.update(chunk)
            lines.append(f'sha256.{name}={digest.hexdigest()}')
        manifest.write_text('\n'.join(lines) + '\n')
    print('Bundled:', ', '.join(order))
