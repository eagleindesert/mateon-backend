#!/usr/bin/env bash
# 소스를 빌드해 DockerHub에 게시한다. 실행 환경은 start.sh에서 선택한다.
set -euo pipefail

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
mode=server
username=""
image_name=""
tag=""
latest_too=true
push=true
platform="linux/arm64"
declare -A supplied=()

usage() {
  cat <<'EOF'
사용법: ./build-push.sh [VERSION] [옵션]

  --mode server|loadtest|ai-stub  게시할 구성 (기본: server)
  --tag VERSION                 이미지 태그 (생략하면 다음 patch 버전 자동 계산)
  --platform PLATFORM           대상 플랫폼 (기본: linux/arm64)
  --username NAME               DockerHub 사용자명
  --image-name NAME             앱 이미지 이름 (기본: mateon-backend)
  --no-latest                   latest 태그는 갱신하지 않음
  --no-push                     게시 대신 로컬 Docker에 이미지 로드
  -h, --help                    사용법

server는 앱 이미지를, loadtest/ai-stub은 앱과 AI 스텁 이미지를 빌드합니다.
앱 코드는 모든 모드에서 동일하며, 테스트용 설정은 배포 Compose가 주입합니다.
AI_STUB_DOCKERHUB_IMAGE로 스텁 이미지 이름을 지정합니다 (기본: mateon-ai-stub).
앱과 스텁은 같은 태그와 플랫폼으로 게시합니다.

자격증명: DOCKERHUB_USERNAME, DOCKERHUB_TOKEN 또는 scripts/docker/.env
토큰이 없으면 기존 docker login 세션을 사용합니다.
자동 버전 계산에는 uv가 필요합니다. --no-push는 플랫폼 하나만 지원합니다.

예시:
  ./build-push.sh --mode server --tag v1.2.3
  ./build-push.sh --mode loadtest --tag v1.2.3
  ./build-push.sh --mode loadtest --tag local --platform linux/amd64 --no-push --no-latest
EOF
}

invalid() { printf '%s\n' "$*" >&2; exit 2; }

while (($#)); do
  case "$1" in
    --mode|--username|--image-name|--tag|--platform)
      option=$1
      (($# >= 2)) && [[ -n "$2" && "$2" != -* ]] || invalid "$option 값이 필요합니다."
      [[ -z "${supplied[$option]:-}" ]] || invalid "$option 옵션은 한 번만 지정하세요."
      supplied[$option]=1
      case "$option" in
        --mode) mode=$2 ;;
        --username) username=$2 ;;
        --image-name) image_name=$2 ;;
        --tag) tag=$2 ;;
        --platform) platform=$2 ;;
      esac
      shift 2 ;;
    --no-latest) latest_too=false; shift ;;
    --no-push) push=false; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) invalid "알 수 없는 옵션: $1 (--help 참고)" ;;
    *)
      [[ -n "$1" && -z "${supplied[--tag]:-}" ]] || invalid '이미지 태그는 한 번만 지정하세요.'
      supplied[--tag]=1
      tag=$1
      shift ;;
  esac
done

case "$mode" in server|loadtest|ai-stub) ;; *) invalid "지원하지 않는 모드: $mode" ;; esac
if [[ -n "$tag" ]]; then
  [[ "$tag" =~ ^[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}$ ]] || invalid "올바른 Docker 이미지 태그를 지정하세요: $tag"
fi
[[ "$platform" =~ ^[a-z0-9_-]+/[a-z0-9_-]+(/[a-z0-9_.-]+)?(,[a-z0-9_-]+/[a-z0-9_-]+(/[a-z0-9_.-]+)?)*$ ]] \
  || invalid "올바른 플랫폼을 지정하세요: $platform"
if [[ "$push" == false && "$platform" == *,* ]]; then
  invalid '--no-push는 플랫폼 하나만 지정하세요.'
fi

step() { printf '\n==> %s\n' "$*"; }
ok() { printf '  [OK] %s\n' "$*"; }
warn() { printf '  [!] %s\n' "$*" >&2; }
fail() { printf '  [X] %s\n' "$*" >&2; exit 1; }

step "프로젝트 루트: $project_root"
step '사전 점검'
command -v docker >/dev/null 2>&1 || fail 'docker CLI를 찾을 수 없습니다.'
docker info >/dev/null 2>&1 || fail 'Docker 데몬에 연결할 수 없습니다.'
ok 'docker 데몬 연결 확인'
[[ -f "$project_root/Dockerfile" ]] || fail "Dockerfile이 없습니다: $project_root/Dockerfile"
[[ -f "$project_root/.dockerignore" ]] || fail '.dockerignore가 없습니다.'

step 'DockerHub 자격증명 로드'
# 기존 자격증명 파일과 호환하되 셸 코드로 실행하지 않는다.
# 파일은 환경변수의 빈 값만 보충하며 DockerHub 자격증명 두 항목만 읽는다.
credentials_file="$project_root/scripts/docker/.env"
if [[ -f "$credentials_file" ]]; then
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
    [[ "$line" == *=* ]] || continue
    key="${line%%=*}"
    key="${key//[[:space:]]/}"
    case "$key" in DOCKERHUB_USERNAME|DOCKERHUB_TOKEN) ;; *) continue ;; esac
    [[ -z "${!key:-}" ]] || continue
    value="${line#*=}"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    if [[ "$value" == \"*\" || "$value" == \'*\' ]]; then
      value="${value:1:${#value}-2}"
    fi
    export "$key=$value"
  done < "$credentials_file"
  ok '.env 파일에서 자격증명 로드'
fi
username="${username:-${DOCKERHUB_USERNAME:-}}"
image_name="${image_name:-${DOCKERHUB_IMAGE:-mateon-backend}}"
stub_image="${AI_STUB_DOCKERHUB_IMAGE:-mateon-ai-stub}"
[[ -n "$username" ]] || invalid '사용자명은 --username 또는 DOCKERHUB_USERNAME으로 지정하세요.'
[[ "$username" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || invalid 'DockerHub 사용자명을 확인하세요.'
[[ "$image_name" =~ ^[a-z0-9][a-z0-9_.-]*$ ]] || invalid '앱 이미지 이름을 확인하세요.'
if [[ "$mode" != server ]]; then
  [[ "$stub_image" =~ ^[a-z0-9][a-z0-9_.-]*$ ]] || invalid 'AI 스텁 이미지 이름을 확인하세요.'
  [[ "$image_name" != "$stub_image" ]] || invalid '앱과 AI 스텁 이미지 이름은 달라야 합니다.'
  [[ -f "$project_root/scripts/test/jmeter/Dockerfile.ai-stub" ]] || fail 'AI 스텁 Dockerfile이 없습니다.'
fi
token="${DOCKERHUB_TOKEN:-}"
if [[ "$token" == dckr_pat_replace* || "$token" == *replace-with-your* ]]; then
  warn 'DOCKERHUB_TOKEN 예시 값을 무시하고 기존 로그인 세션을 사용합니다.'
  token=""
fi
ok "사용자: $username"

if [[ -z "$tag" ]]; then
  step '다음 버전 자동 계산 (DockerHub 조회)'
  tag="$(DOCKERHUB_USERNAME="$username" DOCKERHUB_IMAGE="$image_name" TAG='' GITHUB_OUTPUT='' \
    bash "$project_root/scripts/docker/next-dockerhub-tag.sh")"
  ok "새 버전: $tag"
fi
[[ "$tag" =~ ^[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}$ ]] || fail '계산된 이미지 태그가 올바르지 않습니다.'
step '빌드 대상'
printf '  모드: %s\n  태그: %s\n  플랫폼: %s\n' "$mode" "$tag" "$platform"

step "buildx 준비 ($platform)"
docker buildx version >/dev/null 2>&1 || fail 'docker buildx를 찾을 수 없습니다.'
builder_name='mateon-builder'
builder_ready=false
cleanup() {
  if [[ "$builder_ready" == true ]]; then
    step 'buildx 빌더 컨테이너 정리'
    if docker buildx stop "$builder_name" >/dev/null 2>&1; then
      ok "buildx 빌더 컨테이너 종료: $builder_name"
    else
      warn "buildx 빌더 컨테이너 종료 실패: $builder_name"
    fi
  fi
}
trap cleanup EXIT
builder_node="${builder_name}0"
builder_container="buildx_buildkit_${builder_node}"
# 이전 버전으로 생성한 컨테이너에도 즉시 적용한다. 캐시/볼륨은 유지한다.
if docker container inspect "$builder_container" >/dev/null 2>&1; then
  docker update --restart=no "$builder_container" >/dev/null || fail '기존 빌더 재시작 정책 변경 실패'
fi
# 같은 노드의 설정도 갱신해야 컨테이너가 재생성되어도 정책이 유지된다.
docker buildx create --name "$builder_name" --node "$builder_node" \
  --driver docker-container --driver-opt restart-policy=no --use >/dev/null \
  || fail 'buildx 빌더 생성/설정 실패'
builder_ready=true
ok "buildx 빌더 준비 (자동 재시작 안 함): $builder_name"

if [[ "$push" == true ]]; then
  step 'DockerHub 로그인'
  if [[ -n "$token" ]]; then
    printf '%s\n' "$token" | docker login --username "$username" --password-stdin || fail 'docker login 실패'
    ok '토큰으로 로그인 성공'
  else
    warn 'DOCKERHUB_TOKEN 미설정. 기존 docker login 세션을 사용합니다.'
  fi
fi

build_image() {
  local name=$1 dockerfile=$2
  local repo="$username/$name"
  local -a args=(buildx build --builder "$builder_name" --platform "$platform"
    -f "$project_root/$dockerfile" -t "$repo:$tag")
  if [[ "$latest_too" == true && "$tag" != latest ]]; then args+=(-t "$repo:latest"); fi
  if [[ "$push" == true ]]; then args+=(--push); else args+=(--load); fi
  args+=("$project_root")
  step "이미지 빌드: $repo:$tag"
  docker "${args[@]}" || fail "이미지 빌드/게시 실패: $repo:$tag"
  ok "$repo:$tag"
}

build_image "$image_name" Dockerfile
if [[ "$mode" != server ]]; then
  build_image "$stub_image" scripts/test/jmeter/Dockerfile.ai-stub
fi

if [[ "$push" == true ]]; then
  ok "빌드 및 push 완료 ($mode)"
  printf '  서버에서 실행: ./start.sh --mode %s --tag %s\n' "$mode" "$tag"
else
  ok "빌드 및 로컬 로드 완료 ($mode)"
fi
