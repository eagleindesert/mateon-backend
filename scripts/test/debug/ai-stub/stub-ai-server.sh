#!/usr/bin/env bash
# FastAPI 계약을 흉내내는 AI 스텁. HTTP 서버 구현은 Python 표준 라이브러리 사용.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "$script_dir/_stub_ai_server.py" "$@"
