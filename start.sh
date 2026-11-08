#!/usr/bin/env bash
# 이 파일, docker-compose-deployment.yaml, .env 만 있으면 실행할 수 있다.
set -euo pipefail

if (($# > 1)); then
  printf '사용법: %s [VERSION]\n' "$0" >&2
  exit 2
fi
if [[ "${1:-}" == --help || "${1:-}" == -h ]]; then
  printf '사용법: %s [VERSION]\n버전 생략: up -d / 버전 지정: 해당 태그 pull 후 up -d\n' "$0"
  exit 0
fi

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

if (($#)); then
  [[ "$1" =~ ^[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}$ ]] || {
    printf '올바른 Docker 이미지 태그를 지정하세요: %s\n' "$1" >&2
    exit 2
  }
  export TAG="$1"
  docker compose -f docker-compose-deployment.yaml pull
fi

exec docker compose -f docker-compose-deployment.yaml up -d
