#!/usr/bin/env bash
# 기존 호출과의 호환용 진입점. 빌드·푸시 구현은 루트 build-push.sh에 둔다.
set -euo pipefail
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
exec bash "$project_root/build-push.sh" "$@"
