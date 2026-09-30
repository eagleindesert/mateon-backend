#!/usr/bin/env bash
set -u
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/../00_common.sh"
label=''; email=''; password=''; base_url="$mateon_base_url"; color=Magenta
while (($#)); do
  case "$1" in
    --label|--email|--password|--base-url|--color)
      (($# >= 2)) || exit 2
      case "$1" in
        --label) label=$2 ;; --email) email=$2 ;; --password) password=$2 ;;
        --base-url) base_url=$2 ;; --color) color=$2 ;;
      esac
      shift 2 ;;
    -h|--help) echo '사용법: notification-client.sh --label LABEL --email EMAIL --password PASSWORD [--base-url URL]'; exit 0 ;;
    *) mateon_color_printf Red '%s\n' "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
[[ -n "$label" && -n "$email" && -n "$password" ]] || { mateon_color_printf Red '%s\n' 'label/email/password가 필요합니다.' >&2; exit 2; }
mateon_color_printf "$color" '================================================================\n 실시간 알림(SSE) 뷰어 [%s] %s\n================================================================\n' "$label" "$email"
body="$(python3 -c 'import json,sys; print(json.dumps({"email":sys.argv[1],"password":sys.argv[2]}))' "$email" "$password")"
mateon_base_url="$base_url"
invoke_api --method POST --path /api/auth/login --title "로그인 ($label)" --body "$body"
token="$(json_get "$mateon_response" data.accessToken)"
printf '\n'
[[ -n "$token" ]] || { mateon_color_printf Red '%s\n' "로그인 실패: $email" >&2; exit 1; }
sse_url="$base_url/api/notifications/subscribe"
mateon_color_printf "$color" '실시간 알림 구독 중: %s (종료: Ctrl+C)\n' "$sse_url"
current_event=message
while IFS= read -r line; do
  line="${line%$'\r'}"
  if [[ -z "$line" ]]; then current_event=message; continue; fi
  [[ "$line" == :* ]] && continue
  if [[ "$line" == event:* ]]; then current_event="${line#event:}"; current_event="${current_event# }"; continue; fi
  [[ "$line" == data:* ]] || continue
  data="${line#data:}"; data="${data# }"
  case "$current_event" in
    connect) mateon_color_printf DarkGray '[연결됨] %s\n' "$data" ;;
    notification)
      if [[ "$data" == \{* ]]; then
        title="$(json_get "$data" title)"; content="$(json_get "$data" content)"
        type="$(json_get "$data" type)"; id="$(json_get "$data" id)"
        mateon_color_printf "$color" '\n🔔 %s\n' "$title"
        mateon_color_printf Yellow '   %s (type=%s, id=%s)\n' "$content" "$type" "$id"
      else
        mateon_color_printf "$color" '🔔 %s\n' "$data"
      fi ;;
    *) mateon_color_printf DarkGray '[%s] %s\n' "$current_event" "$data" ;;
  esac
done < <(curl -sSN -H "Authorization: Bearer $token" -H 'Accept: text/event-stream' "$sse_url")
