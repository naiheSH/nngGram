import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('posix_cache', Path(__file__).with_name('posix_native_cache.py'))
cache = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cache)


class PosixCacheTests(unittest.TestCase):
    def test_legacy_migration_requires_identical_recipe_and_host(self):
        original = cache.identities(cache.ROOT, 'macOS', 'ARM64', 'macos-15')
        self.assertEqual(f'desktop-native-v3-macOS-ARM64-macos-15-{cache.LEGACY_ARCHIVE}', original['legacy_key'])
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in cache.INPUTS:
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((cache.ROOT / name).read_bytes())
            windows_script = root / 'scripts/prepare-media.ps1'
            windows_script.write_text('Windows-only repair', encoding='utf-8')
            self.assertEqual(original, cache.identities(root, 'macOS', 'ARM64', 'macos-15'))
            changed = root / 'scripts/build-tdlib.sh'
            changed.write_bytes(changed.read_bytes() + b'\n# changed compiler flags\n')
            updated = cache.identities(root, 'macOS', 'ARM64', 'macos-15')
            self.assertNotEqual(original['key'], updated['key'])
            self.assertEqual('', updated['legacy_key'])
            self.assertNotEqual(original['key'], cache.identities(cache.ROOT, 'macOS', 'X64', 'macos-15-intel')['key'])
