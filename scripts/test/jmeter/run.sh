#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
command -v uv >/dev/null || { printf '테스트 실행기에는 uv가 필요합니다.\n' >&2; exit 1; }
exec uv run --offline --no-project python "$script_dir/runner.py" "$@"
