#!/usr/bin/env bash
# 모든 모드는 이 파일, docker-compose-deployment.yaml, .env, .env.secret으로 실행된다.
# 모든 모드에서 DockerHub에 게시된 이미지만 pull해 실행한다. AI 스텁도 빌드하지 않는다.
# 이미지 빌드·푸시는 build-push.sh, 로컬 빌드·실행은 scripts/build-start.sh에서 담당한다.
set -euo pipefail

usage() {
  cat <<'EOF'
사용법: ./start.sh [VERSION] [옵션]

  --mode server|loadtest|ai-stub  실행 환경 (기본: server)
  --tag VERSION                 앱 이미지 태그
  --db-pool-size NUMBER          DB 연결 풀 크기 (1~10000)
  --port NUMBER                 HTTP 접속 포트 (1~65535)
  -h, --help                    사용법

옵션을 생략하면 선택한 환경의 설정을 사용합니다.
  공통:     .env, .env.secret에서 이미지·실행 설정을 읽습니다.
  server:   APP_PORT 기본 8081 / DB 풀 10
  테스트:   LOADTEST_PORT 기본 18081 / DB 풀 10, 테스트 자격증명만 주입

예시:
  ./start.sh v1.2.3
  ./start.sh --mode loadtest --tag v1.2.3 --db-pool-size 20 --port 18081
  ./start.sh --mode ai-stub

테스트 모드는 별도 DB와 내부 네트워크의 AI 스텁을 사용합니다.
모든 이미지를 DockerHub에서 pull한 뒤 실행하며, 로컬 빌드는 하지 않습니다.
이미지 빌드·게시: ./build-push.sh --help
소스 빌드 후 실행: ./scripts/build-start.sh --help
실제 부하 실행: ./scripts/test/jmeter/run.sh --help
EOF
}

fail() { printf '%s\n' "$*" >&2; exit 2; }
mode=server
tag=''
pool_size=''
http_port=''
declare -A supplied=()

while (($#)); do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --mode|--tag|--db-pool-size|--port)
      option=$1
      (($# >= 2)) && [[ -n "$2" && "$2" != --* ]] || fail "$option 값이 필요합니다."
      [[ -z "${supplied[$option]:-}" ]] || fail "$option 옵션은 한 번만 지정하세요."
      supplied[$option]=1
      case "$option" in
        --mode) mode=$2 ;;
        --tag) tag=$2 ;;
        --db-pool-size) pool_size=$2 ;;
        --port) http_port=$2 ;;
      esac
      shift 2 ;;
    -*) fail "알 수 없는 옵션: $1 (--help 참고)" ;;
    *)
      [[ -n "$1" && -z "${supplied[--tag]:-}" ]] || fail '이미지 태그는 한 번만 지정하세요.'
      supplied[--tag]=1
      tag=$1
      shift ;;
  esac
done

case "$mode" in server|loadtest|ai-stub) ;; *) fail "지원하지 않는 실행 모드: $mode" ;; esac
if [[ -n "$tag" ]]; then
  [[ "$tag" =~ ^[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}$ ]] || fail "올바른 Docker 이미지 태그를 지정하세요: $tag"
  export TAG="$tag"
fi
if [[ -n "$pool_size" ]]; then
  [[ "$pool_size" =~ ^[1-9][0-9]{0,4}$ ]] && ((pool_size <= 10000)) || fail 'DB 풀 크기는 1~10000 정수여야 합니다.'
  export DB_POOL_SIZE="$pool_size"
fi
if [[ -n "$http_port" ]]; then
  [[ "$http_port" =~ ^[1-9][0-9]{0,4}$ ]] && ((http_port <= 65535)) || fail '포트는 1~65535 정수여야 합니다.'
  if [[ "$mode" == server ]]; then
    export APP_PORT="$http_port"
  else
    export LOADTEST_PORT="$http_port"
  fi
fi

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
command -v docker >/dev/null || { printf 'Docker Compose가 필요합니다.\n' >&2; exit 1; }

profile=server
if [[ "$mode" != server ]]; then
  profile=loadtest
  export MATEON_TEST_MODE="$mode"
fi
# 셸이나 .env의 COMPOSE_PROFILES가 다른 모드까지 함께 켜지 않도록 고정한다.
export COMPOSE_PROFILES="$profile"
compose=(docker compose --env-file .env --env-file .env.secret
  -f docker-compose-deployment.yaml --profile "$profile")
if [[ "$profile" == loadtest ]]; then
  compose+=(-p mateon-loadtest)
fi

# Compose가 해석한 값에서 공개 설정만 출력한다. env_file의 시크릿 전체를 출력하지 않는다.
# 별도 JSON 파서 없이 배포 디렉터리의 네 파일만으로 실행한다.
printf '실행 모드: %s\n' "$mode"
"${compose[@]}" config | awk '
  function value(line) {
    sub(/^[^:]+:[[:space:]]*/, "", line)
    gsub(/\042/, "", line)
    return line
  }
  /^  [a-zA-Z0-9_-]+:/ { service = $1; sub(/:$/, "", service) }
  (service == "app" || service == "loadtest-app") && /^    image:/ { print "이미지: " value($0) }
  service == "ai-stub" && /^    image:/ { print "AI 스텁 이미지: " value($0) }
  (service == "app" || service == "loadtest-app") && /^      DB_POOL_SIZE:/ { print "DB 풀: " value($0) }
  (service == "app" || service == "loadtest-app") && /^[[:space:]]+published:/ {
    print "HTTP 포트: " value($0)
  }
'

# 태그 생략 시에도 모든 이미지를 pull한다. 실패하면 기존 로컬 이미지로 실행하지 않는다.
# up에서는 추가 pull과 빌드를 막고, 방금 내려받은 이미지로만 컨테이너를 시작한다.
"${compose[@]}" pull --policy always

exec "${compose[@]}" up -d --no-build --pull never --wait --wait-timeout 180
