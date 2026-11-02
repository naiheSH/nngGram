"""Host-specific cache identity; Windows media changes cannot invalidate POSIX C++ builds."""
import argparse
import hashlib
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
INPUTS = ('native/tdlib-source.properties', 'scripts/build-tdlib.sh',
          'scripts/GenerateTdlibNativeMetadata.java', 'scripts/bundle-macos-libraries.py',
          'scripts/prepare-media-macos.sh')
# Exact pre-migration recipe and archive identity, verified on all four POSIX runners.
# This migration is unavailable after any POSIX build input changes.
LEGACY_RECIPE = '714398edbba047b2b56f7436820395961e178ad428f1b255f0d028016ee5ac7d'
LEGACY_ARCHIVE = '2b7847b59bd11a443379d3549df6a22dca335f409f9f7d9e2e65ccc4ee93eb87'


def identities(root, system, architecture, runner):
    digest = hashlib.sha256()
    for name in sorted(INPUTS):
        digest.update(hashlib.sha256((root / name).read_bytes()).digest())
    fingerprint = digest.hexdigest()
    host = f'{system}-{architecture}-{runner}'
    # Bump the recipe version when compiler setup or deployment flags in the action change.
    return {'key': f'desktop-native-v4-{host}-{fingerprint}',
            'legacy_key': f'desktop-native-v3-{host}-{LEGACY_ARCHIVE}' if fingerprint == LEGACY_RECIPE else ''}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--os', required=True, choices=['macOS', 'Linux'])
    parser.add_argument('--arch', required=True, choices=['X64', 'ARM64'])
    parser.add_argument('--runner-image', required=True)
    args = parser.parse_args()
    values = identities(ROOT, args.os, args.arch, args.runner_image)
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
        for name, value in values.items():
            output.write(f'{name}={value}\n')


if __name__ == '__main__':
    main()
