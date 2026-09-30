#!/usr/bin/env bash
# DockerHub 배포. 기본값은 원본 PowerShell 스크립트와 같다.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/../lib/colors.sh"
project_root="$(cd -- "$script_dir/../.." && pwd)"
username=""
image_name="mateon-backend"
tag=""
latest_too=true
push=true
platform="linux/arm64"

usage() {
  cat <<'EOF'
사용법: deploy-dockerhub.sh [--username NAME] [--image-name NAME] [--tag TAG]
                            [--platform PLATFORM] [--no-latest] [--no-push]
자격증명: DOCKERHUB_USERNAME, DOCKERHUB_TOKEN 또는 scripts/docker/.env
EOF
}

while (($#)); do
  case "$1" in
    --username|--image-name|--tag|--platform)
      (($# >= 2)) || { mateon_color_printf Red '%s\n' "인자 값이 필요합니다: $1" >&2; exit 2; }
      case "$1" in
        --username) username=$2 ;;
        --image-name) image_name=$2 ;;
        --tag) tag=$2 ;;
        --platform) platform=$2 ;;
      esac
      shift 2 ;;
    --no-latest) latest_too=false; shift ;;
    --no-push) push=false; shift ;;
    -h|--help) usage; exit 0 ;;
    *) mateon_color_printf Red '%s\n' "알 수 없는 인자: $1" >&2; usage >&2; exit 2 ;;
  esac
done

step() { mateon_color_printf Cyan '\n==> %s\n' "$*"; }
ok() { mateon_color_printf Green '  [OK] %s\n' "$*"; }
warn() { mateon_color_printf Yellow '  [!] %s\n' "$*" >&2; }
fail() { mateon_color_printf Red '  [X] %s\n' "$*" >&2; exit 1; }

step "프로젝트 루트: $project_root"
step '사전 점검'
command -v docker >/dev/null 2>&1 || fail 'docker CLI를 찾을 수 없습니다.'
docker info >/dev/null 2>&1 || fail 'Docker 데몬에 연결할 수 없습니다.'
ok 'docker 데몬 연결 확인'
[[ -f "$project_root/Dockerfile" ]] || fail "Dockerfile이 없습니다: $project_root/Dockerfile"
if [[ ! -f "$project_root/.dockerignore" ]]; then
  warn '.dockerignore가 없습니다. 빌드 컨텍스트를 확인하세요.'
elif grep -Eq '(^|/)\.env|\*\.env' "$project_root/.dockerignore"; then
  ok '.dockerignore에서 .env 제외 확인'
else
  warn '.dockerignore에 .env 제외 규칙이 보이지 않습니다.'
fi

step 'DockerHub 자격증명 로드'
if [[ -f "$script_dir/../../scripts/docker/.env" ]]; then
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
    [[ "$line" == *=* ]] || continue
    key="${line%%=*}"
    key="${key//[[:space:]]/}"
    value="${line#*=}"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    export "$key=$value"
  done < "$script_dir/../../scripts/docker/.env"
  ok '.env 파일에서 자격증명 로드'
fi
username="${username:-${DOCKERHUB_USERNAME:-}}"
[[ -n "$username" ]] || fail '사용자명은 --username 또는 DOCKERHUB_USERNAME으로 지정하세요.'
token="${DOCKERHUB_TOKEN:-}"
if [[ "$token" == dckr_pat_replace* || "$token" == *replace-with-your* ]]; then
  warn 'DOCKERHUB_TOKEN 예시 값을 무시하고 기존 로그인 세션을 사용합니다.'
  token=""
fi
ok "사용자: $username"

if [[ -z "$tag" ]]; then
  step '다음 버전 자동 계산 (DockerHub 조회)'
  tag="$(DOCKERHUB_USERNAME="$username" DOCKERHUB_IMAGE="$image_name" \
    "$script_dir/next-dockerhub-tag.sh")"
  ok "새 버전: $tag"
fi
repo="$username/$image_name"
full_tag="$repo:$tag"
latest_tag="$repo:latest"
step '빌드 대상'
printf '  이미지 : %s\n  플랫폼 : %s\n' "$full_tag" "$platform"
if [[ "$latest_too" == true && "$tag" != latest ]]; then printf '  추가태그: %s\n' "$latest_tag"; fi

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
if docker buildx inspect "$builder_name" >/dev/null 2>&1; then
  docker buildx use "$builder_name" >/dev/null || fail 'buildx 빌더 선택 실패'
  ok "buildx 빌더 사용: $builder_name"
else
  docker buildx create --name "$builder_name" --use >/dev/null || fail 'buildx 빌더 생성 실패'
  ok "buildx 빌더 생성: $builder_name"
fi
builder_ready=true

if [[ "$push" == true ]]; then
  step 'DockerHub 로그인'
  if [[ -n "$token" ]]; then
    printf '%s\n' "$token" | docker login --username "$username" --password-stdin || fail 'docker login 실패'
    ok '토큰으로 로그인 성공'
  else
    warn 'DOCKERHUB_TOKEN 미설정. 기존 docker login 세션을 사용합니다.'
  fi
fi

step "이미지 빌드 ($platform)"
build_args=(buildx build --platform "$platform" -f "$project_root/Dockerfile" -t "$full_tag")
if [[ "$latest_too" == true && "$tag" != latest ]]; then build_args+=(-t "$latest_tag"); fi
if [[ "$push" == true ]]; then build_args+=(--push); else build_args+=(--load); fi
build_args+=("$project_root")
docker "${build_args[@]}" || fail '이미지 빌드 실패'
if [[ "$push" == true ]]; then
  ok "빌드 및 push 완료: $full_tag ($platform)"
  [[ "$latest_too" == true && "$tag" != latest ]] && ok "push 완료: $latest_tag"
  step '배포 완료'
  mateon_color_printf Green '  docker pull %s\n' "$full_tag"
else
  ok "빌드/로드 완료: $full_tag"
fi
