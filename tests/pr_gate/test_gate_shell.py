"""Exercise the real Bash entry point with read-only, offline GitHub responses."""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

from test_versions import ROOT, fixture


BASH = os.environ.get("PR_GATE_BASH") or (shutil.which("bash") if os.name != "nt" else None)


@unittest.skipUnless(BASH, "Bash required; set PR_GATE_BASH on Windows")
class ShellGateTest(unittest.TestCase):
    def run_gate(self, modern=True, malformed_files=False, release_failure=False):
        with tempfile.TemporaryDirectory() as folder:
            from pathlib import Path
            data = Path(folder)
            files = fixture(modern) + [
                {"filename": "native/jni/example.cpp", "patch": "+// native change"},
                {"filename": "scripts/check_pr_versions.py", "patch": "+# gate change"},
            ]
            branch = "libxposed-api-102-migration" if modern else "main"
            payloads = {
                "meta.tsv": f"candidate\t20\t10\t{len(files)}\tcontributor\t{branch}\n",
                "diff.txt": "+// synthetic changes only\n",
                "files.json": json.dumps([files[:2], files[2:]]),
                "releases.json": json.dumps([
                    [{"draft": True, "tag_name": "1040-1.8.18-api102"}],
                    [{"draft": False, "tag_name": "1032-1.8.10-api102"},
                     {"draft": False, "tag_name": "32-1.8.10"}],
                ]),
            }
            if malformed_files:
                payloads["files.json"] = "invalid JSON"
            for name, text in payloads.items():
                (data / name).write_text(text, encoding="utf-8")
            env = dict(os.environ, PYTHONUTF8="1", PR_GATE_PYTHON=Path(sys.executable).as_posix(),
                       TEST_DATA_ROOT=data.as_posix(), TEST_RELEASE_FAILURE=str(int(release_failure)))
            probe = r'''
python() { "$PR_GATE_PYTHON" "$@"; }
sleep() { :; }
gh() {
  # The real CLI rejects combining these options. Preserve that contract.
  if [[ "$*" == *--slurp* ]] && [[ "$*" == *--jq* ]]; then return 2; fi
  if [ "$1" = pr ] && [ "$2" = view ]; then cat "$TEST_DATA_ROOT/meta.tsv"
  elif [ "$1" = pr ] && [ "$2" = diff ]; then cat "$TEST_DATA_ROOT/diff.txt"
  elif [ "$1" = api ] && [[ "$*" == *pulls/11/files* ]]; then cat "$TEST_DATA_ROOT/files.json"
  elif [ "$1" = api ] && [[ "$*" == *releases?* ]]; then
    [ "$TEST_RELEASE_FAILURE" = 0 ] || return 2
    cat "$TEST_DATA_ROOT/releases.json"
  else return 2; fi
}
export -f python gh sleep
bash scripts/pr_gate.sh 11 --no-comment
'''
            return subprocess.run([BASH, "-c", probe], cwd=ROOT, env=env, encoding="utf-8",
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=20)

    def test_both_channels_pagination_and_manual_gates(self):
        for modern in (True, False):
            with self.subTest(modern=modern):
                result = self.run_gate(modern)
                self.assertEqual(result.returncode, 1, result.stdout)
                self.assertIn("G7 版本一致且递增", result.stdout)
                self.assertIn("❌ **G3 native/jni 改动**", result.stdout)
                self.assertIn("❌ **G4 构建/发布脚本被改动**", result.stdout)

    def test_malformed_file_pages_fail_as_tool_error(self):
        result = self.run_gate(malformed_files=True)
        self.assertEqual(result.returncode, 2, result.stdout)
        self.assertIn("解析文件清单失败", result.stdout)

    def test_release_query_failure_never_uses_zero(self):
        result = self.run_gate(release_failure=True)
        self.assertEqual(result.returncode, 2, result.stdout)
        self.assertIn("取发布版本失败", result.stdout)


if __name__ == "__main__":
    unittest.main()
