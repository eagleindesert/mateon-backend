#!/usr/bin/env bash
set -u
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/00_common.sh"
user_b_email="${MATEON_USERB_EMAIL:-}"; user_b_password="${MATEON_USERB_PASSWORD:-}"
while (($#)); do
  case "$1" in
    --user-b-email) user_b_email=${2:?이메일이 필요합니다}; shift 2 ;;
    --user-b-password) user_b_password=${2:?비밀번호가 필요합니다}; shift 2 ;;
    -h|--help) echo '사용법: 10_chat.sh [--user-b-email EMAIL --user-b-password PASSWORD]'; exit 0 ;;
    *) mateon_color_printf Red '%s\n' "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
mateon_color_printf Magenta '\n########## 10. Chat (REST + WebSocket/STOMP) ##########\n'
token_a="$(get_access_token || true)"
[[ -n "$token_a" ]] || { mateon_color_printf Red '%s\n' 'A accessToken이 없습니다.' >&2; exit 1; }
id_a="$(jwt_subject "$token_a")"
[[ "$id_a" =~ ^[0-9]+$ ]] && result=true || result=false
assert_test '10.0 유저 A userId 확보' "$result" "userIdA=$id_a"
body="$(python3 -c 'import json,sys; print(json.dumps({"email":sys.argv[1],"password":sys.argv[2]}))' "$user_b_email" "$user_b_password")"
invoke_api --method POST --path /api/auth/login --title '10.0 B 로그인' --body "$body"
token_b="$(json_get "$mateon_response" data.accessToken)"
printf '\n'
[[ -n "$token_b" ]] || { mateon_color_printf Red '%s\n' 'B 로그인 실패' >&2; write_test_summary; exit 1; }
id_b="$(jwt_subject "$token_b")"
[[ "$id_b" =~ ^[0-9]+$ && "$id_b" != "$id_a" ]] && result=true || result=false
assert_test '10.0 유저 B userId 확보' "$result" "userIdB=$id_b"
[[ "$id_b" != "$id_a" ]] || { write_test_summary; exit 1; }
trap 'save_access_token "$token_a"' EXIT
save_access_token "$token_a"
dm_body="$(python3 -c 'import json,sys; print(json.dumps({"targetUserId":int(sys.argv[1])}))' "$id_b")"
invoke_api --method POST --path /api/chat/rooms/dm --auth --title '10.1 DM 방 생성 (A→B)' --body "$dm_body"
room_id="$(json_get "$mateon_response" data.roomId)"
printf '\n'
[[ -n "$room_id" ]] && result=true || result=false
assert_test '10.1 roomId 반환' "$result" "roomId=$room_id"
invoke_api --method POST --path /api/chat/rooms/dm --auth --title '10.2 DM 방 재생성 (멱등)' --body "$dm_body"
second_id="$(json_get "$mateon_response" data.roomId)"
printf '\n'
[[ "$second_id" == "$room_id" ]] && result=true || result=false
assert_test '10.2 멱등: 같은 roomId 반환' "$result"
[[ "$room_id" =~ ^[0-9]+$ ]] || { write_test_summary; exit 1; }

message="안녕하세요 자동테스트 메시지 $RANDOM$RANDOM"
probe="$(python3 "$script_dir/parallel-chat/_stomp_probe.py" \
  --base-url "$mateon_base_url" --token-a "$token_a" --token-b "$token_b" \
  --room-id "$room_id" --message "$message" || true)"
[[ "$(json_get "$probe" connected)" == true ]] && result=true || result=false
assert_test '10.3 WebSocket 연결' "$result"
if [[ "$result" == true ]]; then
  for side in a b; do
    [[ "$(json_get "$probe" "${side}_received")" == true ]] && result=true || result=false
    assert_test "10.3 ${side^^} 실시간 수신" "$result"
  done
  [[ "$(json_get "$probe" bad_token_rejected)" == true ]] && result=true || result=false
  assert_test '10.4 잘못된 토큰 CONNECT 거부' "$result"
fi

save_access_token "$token_a"
invoke_api --path /api/chat/rooms --auth --title '10.5 내 방 목록 조회 (A)'
rooms=$mateon_response
printf '\n'
room_a="$(python3 - "$rooms" "$room_id" <<'PY'
import json,sys
try: print(json.dumps(next((r for r in json.loads(sys.argv[1])['data'] if str(r.get('roomId'))==sys.argv[2]),{})))
except (ValueError,KeyError,TypeError): print('{}')
PY
)"
[[ "$room_a" != '{}' ]] && result=true || result=false
assert_test '10.5 방 목록에 생성한 방 포함' "$result"
invoke_api --path "/api/chat/rooms/$room_id/messages?size=30" --auth --title '10.6 메시지 이력 조회 (A)'
history=$mateon_response
printf '\n'
count="$(json_data_count "$history")"
((count >= 1)) && result=true || result=false
assert_test '10.6 이력에 메시지 존재' "$result" "count=$count"
last_message_id="$(python3 - "$history" <<'PY'
import json,sys
try:
    rows=json.loads(sys.argv[1]).get('data') or []
    print(rows[-1].get('messageId','') if rows else '')
except (ValueError,AttributeError,TypeError): print('')
PY
)"

save_access_token "$token_b"
invoke_api --path /api/chat/rooms --auth --title '10.7 B 방 목록(읽기 전)'
rooms_b=$mateon_response
printf '\n'
unread="$(python3 - "$rooms_b" "$room_id" <<'PY'
import json,sys
try: print(next((r.get('unreadCount',0) for r in json.loads(sys.argv[1])['data'] if str(r.get('roomId'))==sys.argv[2]),0))
except (ValueError,KeyError,TypeError): print(0)
PY
)"
[[ "$unread" =~ ^[0-9]+$ && "$unread" -ge 1 ]] && result=true || result=false
assert_test '10.7 B 안읽음 수 >= 1' "$result" "unread=$unread"
if [[ "$last_message_id" =~ ^[0-9]+$ ]]; then
  read_body="$(python3 -c 'import json,sys; print(json.dumps({"lastReadMessageId":int(sys.argv[1])}))' "$last_message_id")"
  invoke_api --method POST --path "/api/chat/rooms/$room_id/read" --auth --title '10.8 B 읽음 처리' --body "$read_body"
  printf '\n'
  invoke_api --path /api/chat/rooms --auth --title '10.8 B 방 목록(읽은 후)'
  after=$mateon_response
  printf '\n'
  unread_after="$(python3 - "$after" "$room_id" <<'PY'
import json,sys
try: print(next((r.get('unreadCount',-1) for r in json.loads(sys.argv[1])['data'] if str(r.get('roomId'))==sys.argv[2]),-1))
except (ValueError,KeyError,TypeError): print(-1)
PY
)"
  [[ "$unread_after" == 0 ]] && result=true || result=false
  assert_test '10.8 B 안읽음 수 0' "$result" "unread=$unread_after"
fi
save_access_token "$token_a"
write_test_summary
