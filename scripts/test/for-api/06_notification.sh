#!/usr/bin/env bash
set -u
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/00_common.sh"

printf '\n########## 6. Notification (알림) - /api/notifications ##########\n'
token="$(get_access_token || true)"
if [[ -z "$token" ]]; then
  echo 'accessToken이 없습니다. auth/02_auth.sh를 먼저 실행하세요.' >&2
  exit 1
fi

invoke_api --method GET --path /api/notifications --auth --title '6.2 내 알림 목록 조회'
printf '\n[6.1 실시간 알림 구독 (SSE)] 5초간 스트림을 수신합니다...\n'
sse_url="$mateon_base_url/api/notifications/subscribe"
printf '  -> %s\n' "$sse_url"
sse_out="$(curl -s -N --max-time 5 -H "Authorization: Bearer $token" \
  -H 'Accept: text/event-stream' "$sse_url" || true)"
printf '%s\n' "$sse_out"
printf '\n  (i) SSE 수신 종료 (max-time 5s)\n'
if [[ "$sse_out" =~ event:[[:space:]]*connect ]]; then
  assert_test '6.1b SSE 구독 시 connect 이벤트 수신' true "received=${#sse_out} chars"
else
  assert_test '6.1b SSE 구독 시 connect 이벤트 수신' false "received=${#sse_out} chars"
fi
write_test_summary
