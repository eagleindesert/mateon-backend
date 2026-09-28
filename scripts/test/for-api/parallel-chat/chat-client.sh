#!/usr/bin/env bash
set -u
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/../00_common.sh"
source "$script_dir/_stomp-lib.sh"
label=''; email=''; password=''; room_id=''; base_url="$mateon_base_url"
while (($#)); do
  case "$1" in
    --label|--email|--password|--room-id|--base-url|--color)
      (($# >= 2)) || exit 2
      case "$1" in
        --label) label=$2 ;; --email) email=$2 ;; --password) password=$2 ;;
        --room-id) room_id=$2 ;; --base-url) base_url=$2 ;; --color) : ;;
      esac
      shift 2 ;;
    -h|--help) echo '사용법: chat-client.sh --label A --email EMAIL --password PASSWORD --room-id ID'; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
[[ -n "$label" && -n "$email" && -n "$password" && "$room_id" =~ ^[0-9]+$ ]] || {
  echo 'label/email/password/room-id가 필요합니다.' >&2; exit 2;
}
printf '================================================================\n 실시간 채팅 클라이언트 [%s] %s\n================================================================\n' "$label" "$email"
mateon_base_url="$base_url"
body="$(python3 -c 'import json,sys; print(json.dumps({"email":sys.argv[1],"password":sys.argv[2]}))' "$email" "$password")"
invoke_api --method POST --path /api/auth/login --title "로그인 ($label)" --body "$body"
token="$(json_get "$mateon_response" data.accessToken)"
printf '\n'
[[ -n "$token" ]] || { echo "로그인 실패: $email" >&2; exit 1; }
my_id="$(jwt_subject "$token")"
printf '(i) 로그인 성공. userId=%s\n' "$my_id"
history="$(curl -sS -H "Authorization: Bearer $token" "$base_url/api/chat/rooms/$room_id/messages?size=8" || true)"
python3 - "$history" "$my_id" <<'PY'
import json,sys
try:
    values=json.loads(sys.argv[1]).get('data') or []
    if values: print('\n--- 최근 대화 ---')
    for m in values:
        who='나' if str(m.get('senderId'))==sys.argv[2] else m.get('senderName','')
        print(f"  {who}: {m.get('content','')}")
    if values: print('-----------------\n')
except (ValueError,AttributeError): pass
PY
run_stomp_chat_client --base-url "$base_url" --token "$token" \
  --room-id "$room_id" --my-id "$my_id" --label "$label"
