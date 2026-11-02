"""Offline contract: reject too-new Mach-O dependencies before changing the app."""
import hashlib
import os
from pathlib import Path
import plistlib
import runpy
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


class MacOSDeploymentTests(unittest.TestCase):
    def finalize(self, version, expected_error=False):
        with tempfile.TemporaryDirectory() as directory:
            app = Path(directory) / 'nngGram.app'
            resources = app / 'Contents/app/resources'
            resources.mkdir(parents=True)
            (resources / 'libtdjni.dylib').write_bytes(b'synthetic-native-library')
            manifest = resources / 'manifest.properties'
            original = 'sha256.libtdjni.dylib=old\n'
            manifest.write_text(original)
            plist = app / 'Contents/Info.plist'
            plist.write_bytes(plistlib.dumps({'LSMinimumSystemVersion': '11.0'}))
            with patch.dict(os.environ, {'NNGGRAM_MACOS_MAX_VERSION': '15.0'}), \
                    patch.object(sys, 'argv', ['finalize-macos-app.py', str(app)]), \
                    patch('subprocess.check_output', return_value=f'cmd LC_BUILD_VERSION\n minos {version}\n'), \
                    patch('subprocess.run') as sign:
                if expected_error:
                    with self.assertRaisesRegex(SystemExit, 'exceed macOS 15.0.*libtdjni'):
                        runpy.run_path(str(ROOT / 'scripts/finalize-macos-app.py'), run_name='__main__')
                    self.assertEqual(original, manifest.read_text())
                    self.assertEqual('11.0', plistlib.loads(plist.read_bytes())['LSMinimumSystemVersion'])
                    sign.assert_not_called()
                else:
                    runpy.run_path(str(ROOT / 'scripts/finalize-macos-app.py'), run_name='__main__')
                    self.assertEqual('15.0', plistlib.loads(plist.read_bytes())['LSMinimumSystemVersion'])
                    self.assertIn(hashlib.sha256(b'synthetic-native-library').hexdigest(), manifest.read_text())
                    self.assertEqual(2, sign.call_count)

    def test_older_dependencies_keep_the_declared_supported_baseline(self):
        self.finalize('14.0')

    def test_newer_dependency_cannot_silently_raise_the_installer_requirement(self):
        self.finalize('27.2', expected_error=True)
