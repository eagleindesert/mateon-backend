#!/usr/bin/env bash
set -u
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/../00_common.sh"
email_a="${MATEON_TEST_EMAIL:-}"; password_a="${MATEON_TEST_PASSWORD:-}"; name_a="${MATEON_TEST_NAME:-테스트유저}"
email_b="${MATEON_USERB_EMAIL:-}"; password_b="${MATEON_USERB_PASSWORD:-}"; name_b="${MATEON_USERB_NAME:-채팅메이트}"
while (($#)); do
  case "$1" in
    --user-a-email|--user-a-password|--user-a-name|--user-b-email|--user-b-password|--user-b-name)
      (($# >= 2)) || exit 2
      case "$1" in
        --user-a-email) email_a=$2 ;; --user-a-password) password_a=$2 ;; --user-a-name) name_a=$2 ;;
        --user-b-email) email_b=$2 ;; --user-b-password) password_b=$2 ;; --user-b-name) name_b=$2 ;;
      esac
      shift 2 ;;
    -h|--help) echo '사용법: launch.sh [--user-a-email EMAIL ...] [--user-b-email EMAIL ...]'; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
printf '\n########## parallel-chat launcher ##########\n'
[[ -n "$email_a" && -n "$password_a" && -n "$email_b" && -n "$password_b" ]] || {
  echo 'A/B 계정 이메일과 비밀번호를 설정하세요.' >&2; exit 1;
}
[[ "$email_a" != "$email_b" ]] || { echo 'A와 B는 다른 계정이어야 합니다.' >&2; exit 1; }

ensure_user() {
  local email=$1 password=$2 name=$3 body code ticket token
  body="$(python3 -c 'import json,sys; print(json.dumps({"email":sys.argv[1],"password":sys.argv[2]}))' "$email" "$password")"
  invoke_api --method POST --path /api/auth/login --no-track --title "로그인 시도: $email" --body "$body" >/dev/null
  token="$(json_get "$mateon_response" data.accessToken)"
  if [[ -n "$token" ]]; then ensured_token=$token; return 0; fi
  request="$(python3 -c 'import json,sys; print(json.dumps({"email":sys.argv[1]}))' "$email")"
  invoke_api --method POST --path /api/auth/email/request --title "이메일 인증코드 요청: $email" --body "$request" >/dev/null
  printf '\n  %s로 발송된 인증코드: ' "$email" >&2
  IFS= read -r code
  if [[ -n "$code" ]]; then
    verify="$(python3 -c 'import json,sys; print(json.dumps({"email":sys.argv[1],"code":sys.argv[2]}))' "$email" "$code")"
    invoke_api --method POST --path /api/auth/email/verify --title "인증코드 검증: $email" --body "$verify" >/dev/null
    ticket="$(json_get "$mateon_response" data.verificationToken)"
    if [[ -n "$ticket" ]]; then
      signup="$(python3 - "$email" "$password" "$name" "$ticket" <<'PY'
import json,sys
email,password,name,ticket=sys.argv[1:]
print(json.dumps({"email":email,"password":password,"passwordConfirm":password,
 "name":name,"campus":"JUKJEON","college":"SW융합대학","major":"소프트웨어학과",
 "grade":"3학년","verificationToken":ticket},ensure_ascii=False))
PY
)"
      invoke_api --method POST --path /api/auth/signup --title "회원가입: $email" --body "$signup" >/dev/null
      token="$(json_get "$mateon_response" data.accessToken)"
      if [[ -n "$token" ]]; then ensured_token=$token; return 0; fi
    fi
  fi
  invoke_api --method POST --path /api/auth/login --no-track --title "재로그인: $email" --body "$body" >/dev/null
  token="$(json_get "$mateon_response" data.accessToken)"
  [[ -n "$token" ]] || return 1
  ensured_token=$token
}

ensure_user "$email_a" "$password_a" "$name_a" || true
token_a="${ensured_token:-}"
ensured_token=''
ensure_user "$email_b" "$password_b" "$name_b" || true
token_b="${ensured_token:-}"
[[ -n "$token_a" && -n "$token_b" ]] || { echo '계정 준비 실패' >&2; exit 1; }
id_a="$(jwt_subject "$token_a")"; id_b="$(jwt_subject "$token_b")"
[[ -n "$id_a" && -n "$id_b" && "$id_a" != "$id_b" ]] || { echo 'A/B userId가 같거나 유효하지 않습니다.' >&2; exit 1; }
save_access_token "$token_a"
body="$(python3 -c 'import json,sys; print(json.dumps({"targetUserId":int(sys.argv[1])}))' "$id_b")"
invoke_api --method POST --path /api/chat/rooms/dm --auth --title 'DM 방 생성 (A→B)' --body "$body"
room_id="$(json_get "$mateon_response" data.roomId)"
printf '\n'
[[ "$room_id" =~ ^[0-9]+$ ]] || { echo 'roomId 확보 실패' >&2; exit 1; }

if command -v gnome-terminal >/dev/null 2>&1; then
  terminal=gnome-terminal
elif command -v xterm >/dev/null 2>&1; then
  terminal=xterm
elif command -v x-terminal-emulator >/dev/null 2>&1; then
  terminal=x-terminal-emulator
else
  echo '새 창을 띄울 터미널(gnome-terminal/xterm)이 없습니다.' >&2
  exit 1
fi
start_window() {
  if [[ "$terminal" == gnome-terminal ]]; then
    gnome-terminal -- bash "$@"
  else
    "$terminal" -e bash "$@"
  fi
}
start_window "$script_dir/chat-client.sh" --label A --email "$email_a" --password "$password_a" \
  --room-id "$room_id" --base-url "$mateon_base_url" --color Cyan
sleep 0.4
start_window "$script_dir/chat-client.sh" --label B --email "$email_b" --password "$password_b" \
  --room-id "$room_id" --base-url "$mateon_base_url" --color Green
sleep 0.4
start_window "$script_dir/notification-client.sh" --label B-noti --email "$email_b" \
  --password "$password_b" --base-url "$mateon_base_url" --color Magenta
printf '채팅 창 2개와 알림 창 1개를 시작했습니다. roomId=%s\n' "$room_id"
