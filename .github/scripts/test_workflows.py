"""Static checks for workflow trust and artifact boundaries; requires PyYAML only when installed."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]

class WorkflowTests(unittest.TestCase):
    def test_external_actions_are_commit_pinned(self):
        for path in (ROOT / '.github').rglob('*.yml'):
            for action in re.findall(r'(?m)^\s*-?\s*uses:\s*(\S+)', path.read_text(encoding='utf-8-sig')):
                if not action.startswith('./'):
                    self.assertRegex(action, r'^[\w.-]+/[\w./-]+@[0-9a-f]{40}$')

    def test_workflow_gates_distribution_and_scopes_secrets(self):
        text = (ROOT / '.github/workflows/ci.yml').read_text(encoding='utf-8')
        checks = text.split('  native:')[0]
        self.assertNotIn('secrets.', checks)
        self.assertNotIn('pull_request_target', text)
        self.assertIn("github.repository == vars.DISTRIBUTION_REPOSITORY", text)
        self.assertIn('-RequireTelegramApi', text)
        self.assertNotIn('contents: write', text)
        self.assertIn('compression-level: 0', text)
        self.assertIn('queue: max', text)

    def test_yaml_if_parser_available(self):
        try:
            import yaml
        except ImportError:
            self.skipTest('PyYAML optional; actionlint handles workflow validation separately')
        for path in (ROOT / '.github').rglob('*.yml'):
            self.assertIsInstance(yaml.safe_load(path.read_text(encoding='utf-8-sig')), dict)

    def test_desktop_matrix_and_release_boundaries(self):
        text = (ROOT / '.github/workflows/ci.yml').read_text(encoding='utf-8')
        for profile in ('windows-x64', 'macos-x64', 'macos-arm64', 'linux-x64', 'linux-arm64'):
            self.assertGreaterEqual(text.count('profile: ' + profile), 2)
        distribution = text.split('  distributions:', 1)[1].split('  telegram:', 1)[0]
        self.assertIn('needs: checks', distribution)
        self.assertIn("github.event_name != 'pull_request'", distribution)
        self.assertIn('github.repository == vars.DISTRIBUTION_REPOSITORY', distribution)
        self.assertIn('--release --installer --skip-tests', distribution)
        self.assertIn('xvfb-run -a python scripts/desktop.py', text)
        self.assertNotIn('portableCoreChecks', (ROOT / 'shared/build.gradle.kts').read_text())
