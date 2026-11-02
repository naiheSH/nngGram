#!/usr/bin/env python3
"""Collect installed MSYS2 runtime licenses, including GCC 16's split packages."""
import argparse
from pathlib import Path
import re
import shutil

PACKAGES = ('gcc-libs', 'libwinpthread', 'openssl', 'zlib', 'crt')
LICENSE_NAME = re.compile(r'(?:COPYING|LICENSE|README|COPYRIGHT)[A-Za-z0-9._-]*', re.IGNORECASE)


def collect(toolchain, output):
    root = Path(toolchain) / 'share/licenses'
    groups = {name: [name] for name in PACKAGES}
    # gcc-libs became a metapackage. Keep both GPL and runtime exception texts.
    if not (root / 'gcc-libs').is_dir():
        groups['gcc-libs'] = ['libgcc', 'libstdc++']
    pending = []
    for logical, sources in groups.items():
        for source in sources:
            directory = root / source
            files = [p for p in directory.iterdir() if p.is_file() and not p.is_symlink()
                     and LICENSE_NAME.fullmatch(p.name) and p.stat().st_size > 0] if directory.is_dir() else []
            if not files:
                raise ValueError(f'Missing dependency license: {logical} (installed path: {directory})')
            for path in files:
                suffix = '.' + source.replace('+', 'x') if len(sources) > 1 else ''
                pending.append((path, Path(output) / logical / (path.name + suffix)))
    # Check every source before writing, so a missing package fails before compilation.
    for source, destination in pending:
        destination.parent.mkdir(parents=True, exist_ok=True)
        if source.resolve() != destination.resolve():
            shutil.copyfile(source, destination)
    print(f'Collected {len(pending)} runtime license files from the installed toolchain.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--toolchain', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    collect(args.toolchain, args.output)
