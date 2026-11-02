"""Installed-layout fixtures for the license failure seen on the Windows runner."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('windows_licenses', Path(__file__).resolve().parents[2] / 'scripts/collect-windows-licenses.py')
licenses = importlib.util.module_from_spec(spec)
spec.loader.exec_module(licenses)


class WindowsLicenseTests(unittest.TestCase):
    def prepare(self, root, split):
        names = ['libgcc', 'libstdc++'] if split else ['gcc-libs']
        for name in names + ['libwinpthread', 'openssl', 'zlib', 'crt']:
            directory = root / 'share/licenses' / name
            directory.mkdir(parents=True)
            (directory / 'COPYING').write_text(f'{name} license')
            if name in ('libgcc', 'libstdc++'):
                (directory / 'COPYING.RUNTIME').write_text(f'{name} runtime exception')

    def test_legacy_gcc_layout_remains_supported(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); self.prepare(root, False)
            licenses.collect(root, root / 'output')
            self.assertEqual('gcc-libs license', (root / 'output/gcc-libs/COPYING').read_text())

    def test_split_gcc_keeps_both_runtime_exception_and_copyright_texts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); self.prepare(root, True)
            licenses.collect(root, root / 'output')
            for name in ('libgcc', 'libstdc++'):
                suffix = name.replace('+', 'x')
                for filename in ('COPYING', 'COPYING.RUNTIME'):
                    self.assertEqual((root / 'share/licenses' / name / filename).read_bytes(),
                                     (root / 'output/gcc-libs' / (filename + '.' + suffix)).read_bytes())

    def test_installed_toolchain_can_be_normalized_in_place_without_rewriting_licenses(self):
        for split in (False, True):
            with self.subTest(split=split), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp); self.prepare(root, split)
                licenses.collect(root, root / 'share/licenses')
                self.assertTrue((root / 'share/licenses/gcc-libs').is_dir())
                if split:
                    self.assertEqual('libgcc runtime exception',
                        (root / 'share/licenses/gcc-libs/COPYING.RUNTIME.libgcc').read_text())

    def test_missing_split_component_is_rejected_before_any_output_is_written(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); self.prepare(root, True)
            (root / 'share/licenses/libstdc++/COPYING').unlink()
            (root / 'share/licenses/libstdc++/COPYING.RUNTIME').unlink()
            with self.assertRaisesRegex(ValueError, r'Missing dependency license: gcc-libs.*libstdc\+\+'):
                licenses.collect(root, root / 'output')
            self.assertFalse((root / 'output').exists())
