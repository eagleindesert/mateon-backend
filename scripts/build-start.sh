#!/usr/bin/env bash
# 소스를 빌드하고 같은 Docker 데몬에서 app 컨테이너를 재생성한다.
set -euo pipefail

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
username="${DOCKERHUB_USERNAME:-eagleindesert}"
image_name="${DOCKERHUB_IMAGE:-mateon-backend}"
tag="local"
platform=""

usage() {
  cat <<'EOF'
사용법: scripts/build-start.sh [--tag TAG] [--platform PLATFORM]
                              [--username NAME] [--image-name NAME]
이미지를 로컬에 빌드/로드한 뒤 app 을 재생성한다. DockerHub 에 push 하지 않는다.
플랫폼 기본값은 Docker 데몬의 아키텍처다 (x86: linux/amd64, ARM: linux/arm64).
배포용 ARM 이미지 빌드/푸시는 scripts/docker/deploy-dockerhub.sh 를 사용한다.
EOF
}

while (($#)); do
  case "$1" in
    --tag|--platform|--username|--image-name)
      (($# >= 2)) && [[ -n "$2" ]] || {
        printf '인자 값이 필요합니다: %s\n' "$1" >&2
        exit 2
      }
      case "$1" in
        --tag) tag="$2" ;;
        --platform) platform="$2" ;;
        --username) username="$2" ;;
        --image-name) image_name="$2" ;;
      esac
      shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) printf '알 수 없는 인자: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
done

cd -- "$project_root"
if [[ -z "$platform" ]]; then
  platform="$(docker version --format '{{.Server.Os}}/{{.Server.Arch}}')"
fi
[[ "$platform" != *,* ]] || {
  printf '컨테이너 실행에는 플랫폼 하나만 지정하세요.\n' >&2
  exit 2
}

# 빌드할 이미지와 Compose 가 실행할 이미지를 동일하게 고정한다.
export DOCKERHUB_USERNAME="$username" DOCKERHUB_IMAGE="$image_name" TAG="$tag"
export APP_PLATFORM="$platform" APP_PULL_POLICY=never
docker compose -f docker-compose-deployment.yaml config --quiet

bash "$project_root/scripts/docker/deploy-dockerhub.sh" \
  --username "$username" --image-name "$image_name" --tag "$tag" \
  --platform "$platform" --no-push --no-latest

# app 을 반드시 교체하고, postgres 는 Compose 의 의존성/상태 검사에 맡긴다.
exec docker compose -f docker-compose-deployment.yaml up -d --force-recreate app
