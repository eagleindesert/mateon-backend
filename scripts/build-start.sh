#!/usr/bin/env bash
# 소스를 빌드하고 같은 Docker 데몬에서 실행한다. DockerHub에 push하지 않는다.
# 기본 실행은 bootRun과 같은 개발 Compose·DB를 사용하며 호스트 포트는 8080이다.
# 테스트 모드에서는 앱과 AI 스텁을 모두 빌드한다. pull 전용 실행은 ../start.sh를 사용한다.
set -euo pipefail

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
username="${DOCKERHUB_USERNAME:-eagleindesert}"
image_name="${DOCKERHUB_IMAGE:-mateon-backend}"
tag="local"
platform=""
mode=server
pool_size=''
http_port=''
declare -A supplied=()

usage() {
  cat <<'EOF'
사용법: ./scripts/build-start.sh [VERSION] [옵션]

  --mode server|loadtest|ai-stub  실행 환경 (기본: server = 일반 개발 환경)
  --tag VERSION                 빌드할 이미지 태그 (기본: local)
  --db-pool-size NUMBER          DB 연결 풀 크기 (1~10000)
  --port NUMBER                 HTTP 접속 포트 (1~65535)
  --platform PLATFORM           빌드·실행 플랫폼 (예: linux/amd64)
  --username NAME               DockerHub 사용자명
  --image-name NAME             앱 이미지 이름
  -h, --help                    사용법

앱을 빌드/로드한 뒤 재생성한다. 테스트 모드는 AI 스텁도 같은 태그로 빌드한다.
DockerHub에 push하지 않으며, 직접 빌드한 앱·스텁 이미지는 pull하지 않는다.
DB·프록시 이미지는 로컬에 없으면 pull한다.
플랫폼 기본값은 Docker 데몬의 아키텍처다 (x86: linux/amd64, ARM: linux/arm64).
server는 개발 Compose와 루트 .env / .env.secret을 사용한다 (기본 포트 8080).
테스트 모드는 config/loadtest.properties를 사용한다 (기본 포트 18081).
개발 포트 설정은 DEV_APP_PORT이며 --port로 덮어쓸 수 있다.
앱의 /health가 성공할 때까지 최대 180초 기다린다.

예시:
  ./scripts/build-start.sh --tag local --db-pool-size 20 --port 8080
  ./scripts/build-start.sh --mode loadtest --db-pool-size 20 --port 18081
  ./scripts/build-start.sh --mode ai-stub --platform linux/amd64

배포용 ARM 이미지 빌드/푸시는 scripts/docker/deploy-dockerhub.sh 를 사용한다.
EOF
}

fail() { printf '%s\n' "$*" >&2; exit 2; }

while (($#)); do
  case "$1" in
    --mode|--tag|--db-pool-size|--port|--platform|--username|--image-name)
      option=$1
      (($# >= 2)) && [[ -n "$2" && "$2" != --* ]] || fail "$option 값이 필요합니다."
      [[ -z "${supplied[$option]:-}" ]] || fail "$option 옵션은 한 번만 지정하세요."
      supplied[$option]=1
      case "$option" in
        --mode) mode="$2" ;;
        --tag) tag="$2" ;;
        --db-pool-size) pool_size="$2" ;;
        --port) http_port="$2" ;;
        --platform) platform="$2" ;;
        --username) username="$2" ;;
        --image-name) image_name="$2" ;;
      esac
      shift 2 ;;
    -h|--help) usage; exit 0 ;;
    -*) fail "알 수 없는 옵션: $1 (--help 참고)" ;;
    *)
      [[ -n "$1" && -z "${supplied[--tag]:-}" ]] || fail '이미지 태그는 한 번만 지정하세요.'
      supplied[--tag]=1
      tag=$1
      shift ;;
  esac
done

case "$mode" in server|loadtest|ai-stub) ;; *) fail "지원하지 않는 실행 모드: $mode" ;; esac
[[ "$tag" =~ ^[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}$ ]] || fail "올바른 Docker 이미지 태그를 지정하세요: $tag"
if [[ -n "$pool_size" ]]; then
  [[ "$pool_size" =~ ^[1-9][0-9]{0,4}$ ]] && ((pool_size <= 10000)) || fail 'DB 풀 크기는 1~10000 정수여야 합니다.'
  export DB_POOL_SIZE="$pool_size"
fi
if [[ -n "$http_port" ]]; then
  [[ "$http_port" =~ ^[1-9][0-9]{0,4}$ ]] && ((http_port <= 65535)) || fail '포트는 1~65535 정수여야 합니다.'
  if [[ "$mode" == server ]]; then
    export DEV_APP_PORT="$http_port"
  else
    export APP_PORT="$http_port"
  fi
fi
[[ "$platform" != *,* ]] || fail '컨테이너 실행에는 플랫폼 하나만 지정하세요.'

cd -- "$project_root"
command -v docker >/dev/null || { printf 'Docker Compose가 필요합니다.\n' >&2; exit 1; }
printf '빌드 및 실행 모드: %s\n' "$mode"
if [[ -z "$platform" ]]; then
  if ! platform="$(docker version --format '{{.Server.Os}}/{{.Server.Arch}}')"; then
    printf 'Docker 데몬에 연결할 수 없습니다. Docker Desktop 또는 Docker Engine을 시작하세요.\n' >&2
    exit 1
  fi
fi

# 빌드할 이미지와 Compose 가 실행할 이미지를 동일하게 고정한다.
export DOCKERHUB_USERNAME="$username" DOCKERHUB_IMAGE="$image_name" TAG="$tag"
export APP_PLATFORM="$platform" APP_PULL_POLICY=never
# 개발은 bootRun과 같은 DB 볼륨을 사용한다. 배포 Compose·DB를 기본값으로 쓰지 않는다.
compose=(docker compose -f docker-compose.yml --profile app)
if [[ "$mode" != server ]]; then
  export MATEON_TEST_MODE="$mode"
  export AI_STUB_DOCKERHUB_IMAGE="${AI_STUB_DOCKERHUB_IMAGE:-mateon-ai-stub}" AI_STUB_TAG="$tag"
  [[ "$image_name" != "$AI_STUB_DOCKERHUB_IMAGE" ]] || fail '앱과 AI 스텁 이미지 이름은 달라야 합니다.'
  compose=(docker compose --env-file config/loadtest.properties
    -p mateon-loadtest -f docker-compose-loadtest.yaml)
fi
"${compose[@]}" config --quiet

bash "$project_root/scripts/docker/deploy-dockerhub.sh" \
  --username "$username" --image-name "$image_name" --tag "$tag" \
  --platform "$platform" --no-push --no-latest

if [[ "$mode" == server ]]; then
  # postgres는 개발 볼륨을 유지하고, 앱은 /health가 성공해야 기동 완료로 처리한다.
  exec "${compose[@]}" up -d --no-build --force-recreate --wait --wait-timeout 180 app
fi

# 테스트 Compose에는 build를 두지 않는다. 스텁도 여기서 명시적으로 빌드해 로드한다.
# 스텁 빌드 결과도 --load로 같은 Docker 데몬에 명시적으로 로드한다.
docker build --platform "$platform" --load -f scripts/test/jmeter/Dockerfile.ai-stub \
  -t "$username/$AI_STUB_DOCKERHUB_IMAGE:$AI_STUB_TAG" .

# 방금 빌드한 앱·스텁은 pull_policy=never로 실행한다. DB·프록시는 없을 때만 pull한다.
exec "${compose[@]}" up -d --no-build --force-recreate --wait --wait-timeout 180 app ai-stub proxy
