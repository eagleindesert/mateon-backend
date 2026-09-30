"""Offline regression checks for standalone and full-run API summaries."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


API_DIR = Path(__file__).resolve().parents[1]
SCRIPTS_DIR = API_DIR.parents[1]


class SummaryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.api = self.root / "scripts/test/for-api"
        self.api.mkdir(parents=True)
        shutil.copytree(SCRIPTS_DIR / "lib", self.root / "scripts/lib")
        for name in ("00_common.sh", "99_run_all.sh"):
            shutil.copy(API_DIR / name, self.api / name)
        self.env = dict(os.environ, MATEON_ENV_FILE=str(self.root / "missing-config"),
                        NO_COLOR="1", TMPDIR=str(self.root))
        self.env.pop("MATEON_RESULTS_FILE", None)
        self.env.pop("MATEON_TEST_SECTION", None)

    def run_bash(self, code):
        return subprocess.run(["bash", "-uc", code], cwd=self.api, env=self.env,
                              capture_output=True, text=True)

    def assert_counts(self, output, total, passed, warned, failed):
        for label, count in zip(("전체", "성공", "주의", "실패"),
                                (total, passed, warned, failed)):
            self.assertRegex(output, rf"  {label}: {count}\n")

    def prepare_sections(self, replacements=None, missing=()):
        runner = (self.api / "99_run_all.sh").read_text()
        for name in re.findall(r"^run_test (\S+)", runner, re.M):
            if name in missing:
                continue
            target = self.api / name
            target.parent.mkdir(parents=True, exist_ok=True)
            source = '../00_common.sh' if name.startswith('auth/') else '00_common.sh'
            target.write_text(
                '#!/usr/bin/env bash\nset -u\n'
                f'source "$(dirname "$0")/{source}"\n'
                + (replacements or {}).get(name, 'exit 0\n')
            )

    def final_summary(self, result):
        self.assertIn('===== 전체 테스트 완료 =====', result.stdout)
        return result.stdout.split('===== 전체 테스트 완료 =====', 1)[1]

    def test_full_run_merges_details_without_double_counting(self):
        self.prepare_sections({
            '01_health.sh': """
curl() { printf '{"data":1}\nHTTP_STATUS:200'; }
invoke_api --path /health --title 'health' >/dev/null
assert_test 'quality' false $'detail\twith\nnewline' true
assert_test 'schema' false 'missing id'
write_test_summary
""",
            '03_00_user.sh': """
curl() { printf '{}\nHTTP_STATUS:403'; }
invoke_api --path /private --title 'auth (차단 기대)' >/dev/null
curl() { printf '{}\nHTTP_STATUS:503'; }
invoke_api --path /broken --title 'unavailable' >/dev/null
write_test_summary
""",
        })
        result = self.run_bash('bash ./99_run_all.sh')
        self.assertEqual(result.returncode, 2, result.stderr)
        summary = self.final_summary(result)
        self.assert_counts(summary, 5, 2, 1, 2)
        self.assertIn('정상 차단된 항목 (1개)', summary)
        self.assertIn('[03_00_user.sh] [403] auth (차단 기대)  (GET /private)', summary)
        self.assertIn('[01_health.sh] [WARN] quality  (ASSERT) - detail\twith\nnewline', summary)
        self.assertIn('[01_health.sh] [FAIL] schema  (ASSERT) - missing id', summary)
        self.assertIn('[03_00_user.sh] [503] unavailable  (GET /broken)', summary)
        self.assertNotIn('EXIT_', summary)
        self.assertFalse(list(self.root.glob('tmp.*')), 'result journal was not cleaned up')

    def test_warning_only_does_not_fail_or_leak_between_runs(self):
        self.prepare_sections({'19_ai_gateway.sh': "assert_test 'stub text' false 'real LLM' true\nwrite_test_summary\n"})
        for _ in range(2):
            result = self.run_bash('bash ./99_run_all.sh')
            self.assertEqual(result.returncode, 0, result.stderr)
            summary = self.final_summary(result)
            self.assert_counts(summary, 1, 0, 1, 0)
            self.assertIn('실패 없음 - 주의 1건만 확인하세요', summary)
            self.assertIn('[19_ai_gateway.sh] [WARN] stub text', summary)

    def test_early_exit_and_missing_script_are_named_failures(self):
        self.prepare_sections({'03_00_user.sh': 'exit 7\n'}, missing=('05_team.sh',))
        result = self.run_bash('bash ./99_run_all.sh')
        self.assertEqual(result.returncode, 2)
        summary = self.final_summary(result)
        self.assert_counts(summary, 2, 0, 0, 2)
        self.assertIn('[03_00_user.sh] [EXIT_7]', summary)
        self.assertIn('[05_team.sh] [MISSING]', summary)

    def test_upload_no_track_and_standalone_details(self):
        (self.api / 'image.png').write_bytes(b'fixture')
        result = self.run_bash("""
source ./00_common.sh
curl() { printf '{}\nHTTP_STATUS:503'; }
invoke_api --path /setup --no-track >/dev/null
curl() { printf '{}\nHTTP_STATUS:403'; }
invoke_api_upload --path /upload --file-path image.png --title 'upload (차단 기대)' >/dev/null
assert_test 'warning' false 'check model' true
assert_test 'failure' false 'check schema'
write_test_summary
""")
        self.assertEqual(result.returncode, 1)
        self.assert_counts(result.stdout, 3, 1, 1, 1)
        self.assertIn('[403] upload (차단 기대)  (POST /upload)', result.stdout)
        self.assertIn('[WARN] warning  (ASSERT) - check model', result.stdout)
        self.assertIn('[FAIL] failure  (ASSERT) - check schema', result.stdout)
        self.assertNotIn('/setup', result.stdout)

    def test_exit_code_is_capped_without_losing_failure_count(self):
        result = self.run_bash("""
source ./00_common.sh
for ((i=0; i<260; i++)); do assert_test "failure $i" false >/dev/null; done
write_test_summary
""")
        self.assertEqual(result.returncode, 255)
        self.assert_counts(result.stdout, 260, 0, 0, 260)
        self.assertIn('[FAIL] failure 259', result.stdout)


if __name__ == '__main__':
    unittest.main()
