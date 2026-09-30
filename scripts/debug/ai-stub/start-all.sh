#!/usr/bin/env bash
# AI 스텁 두 개와 백엔드를 시작하고 종료 시 스텁을 정리한다.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd -- "$script_dir/../../.." && pwd)"
port=8000; embedding_dimension=1536; expected_secret=''; router_port=8001
router_force_domain=''; router_failure_mode=none; no_router_stub=false
while (($#)); do
  case "$1" in
    --port|--embedding-dimension|--expected-secret|--router-port|--router-force-domain|--router-failure-mode)
      (($# >= 2)) || { echo "인자 값이 필요합니다: $1" >&2; exit 2; }
      case "$1" in
        --port) port=$2 ;; --embedding-dimension) embedding_dimension=$2 ;;
        --expected-secret) expected_secret=$2 ;; --router-port) router_port=$2 ;;
        --router-force-domain) router_force_domain=$2 ;; --router-failure-mode) router_failure_mode=$2 ;;
      esac
      shift 2 ;;
    --no-router-stub) no_router_stub=true; shift ;;
    -h|--help) echo '사용법: start-all.sh [--port 8000] [--router-port 8001] [--no-router-stub]'; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
[[ "$port" =~ ^[0-9]+$ && "$router_port" =~ ^[0-9]+$ ]] || { echo '포트는 정수여야 합니다.' >&2; exit 2; }
[[ -f "$project_root/.env.secret" ]] || { echo "$project_root/.env.secret이 없습니다." >&2; exit 1; }
grep -Eq '^[[:space:]]*AI_INTERNAL_SECRET[[:space:]]*=[[:space:]]*[^[:space:]]+' "$project_root/.env.secret" || {
  echo '.env.secret의 AI_INTERNAL_SECRET이 비어 있습니다.' >&2; exit 1;
}
port_open() {
  python3 - "$1" <<'PY'
import socket,sys
try:
    with socket.create_connection(('127.0.0.1',int(sys.argv[1])),timeout=0.3): pass
except OSError: sys.exit(1)
PY
}
port_open "$port" && { echo "포트 $port가 이미 사용 중입니다." >&2; exit 1; }
if [[ "$no_router_stub" != true ]]; then
  port_open "$router_port" && { echo "포트 $router_port가 이미 사용 중입니다." >&2; exit 1; }
fi
log_dir="$(mktemp -d)"
stub_pids=()
cleanup() {
  for pid in "${stub_pids[@]}"; do kill "$pid" 2>/dev/null || true; done
  for pid in "${stub_pids[@]}"; do wait "$pid" 2>/dev/null || true; done
  printf '\n스텁 로그: %s\n' "$log_dir"
}
trap cleanup EXIT INT TERM
start_stub() {
  local label=$1 target_port=$2 script=$3; shift 3
  bash "$script" --port "$target_port" "$@" > "$log_dir/$label.log" 2>&1 &
  local pid=$!
  stub_pids+=("$pid")
  for ((i=0; i<50; i++)); do
    if port_open "$target_port"; then
      printf '%s 스텁 준비 완료 (PID %s, 로그 %s)\n' "$label" "$pid" "$log_dir/$label.log"
      return 0
    fi
    kill -0 "$pid" 2>/dev/null || { cat "$log_dir/$label.log" >&2; return 1; }
    sleep 0.3
  done
  echo "$label 스텁이 15초 안에 포트를 열지 않았습니다." >&2
  cat "$log_dir/$label.log" >&2
  return 1
}
stub_args=(--embedding-dimension "$embedding_dimension")
[[ -n "$expected_secret" ]] && stub_args+=(--expected-secret "$expected_secret")
start_stub ai "$port" "$script_dir/stub-ai-server.sh" "${stub_args[@]}"
boot_args=("--ai.base-url=http://localhost:$port")
if [[ "$no_router_stub" != true ]]; then
  router_args=()
  [[ -n "$router_force_domain" ]] && router_args+=(--force-domain "$router_force_domain")
  [[ "$router_failure_mode" != none ]] && router_args+=(--failure-mode "$router_failure_mode")
  start_stub router "$router_port" "$script_dir/stub-spring-ai-server.sh" "${router_args[@]}"
  boot_args+=("--spring.ai.openai.base-url=http://localhost:$router_port/v1"
              '--spring.ai.openai.api-key=' '--airouter.enabled=true')
fi
printf '백엔드 bootRun: %s\n' "${boot_args[*]}"
cd "$project_root"
./gradlew bootRun "--args=${boot_args[*]}"
