#!/usr/bin/env bash
# REST, WebSocket, SockJS Origin 허용 목록을 확인한다.
set -u
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
path=/health; ws_path=/ws-stomp; stomp_path=/ws-stomp/info; base_url=''
while (($#)); do
  case "$1" in
    --path|--ws-path|--stomp-path|--base-url)
      (($# >= 2)) || exit 2
      case "$1" in
        --path) path=$2 ;; --ws-path) ws_path=$2 ;;
        --stomp-path) stomp_path=$2 ;; --base-url) base_url=$2 ;;
      esac
      shift 2 ;;
    -h|--help) echo '사용법: check-cors.sh [--base-url URL] [--path /health] [--ws-path /ws-stomp]'; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
if [[ -f "$script_dir/.env" ]]; then
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" == *=* ]] || continue
    key="${line%%=*}"; key="${key//[[:space:]]/}"
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    value="${line#*=}"; value="${value%$'\r'}"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    if [[ "$value" == \"*\" || "$value" == \'*\' ]]; then value="${value:1:${#value}-2}"; fi
    export "$key=$value"
  done < "$script_dir/.env"
fi
base_url="${base_url:-${MATEON_BASE_URL:-http://localhost:8080}}"
known_origins=('http://localhost:3000' 'http://localhost:5173')
other_origins=('http://evil-example.com' 'https://random-attacker.test' 'http://localhost:9999')

header_value() {
  local raw=$1 name=$2 line
  while IFS= read -r line; do
    line="${line%$'\r'}"
    if [[ "${line,,}" == "${name,,}:"* ]]; then
      line="${line#*:}"; line="${line# }"
      printf '%s' "$line"
      return 0
    fi
  done <<< "$raw"
}
status_code() {
  local raw=$1 line
  line="${raw%%$'\n'*}"
  if [[ "$line" =~ ^HTTP/[0-9.]+[[:space:]]+([0-9]+) ]]; then printf '%s' "${BASH_REMATCH[1]}"; fi
}

check_group() {
  local kind=$1 target=$2 origin known raw status allow reflected blocked
  local other_passed=0 known_blocked=0
  printf '\n########## %s CORS 확인 ##########\n  대상: %s\n' "$kind" "$base_url$target"
  for known in true false; do
    if [[ "$known" == true ]]; then origins=("${known_origins[@]}"); else origins=("${other_origins[@]}"); fi
    for origin in "${origins[@]}"; do
      if [[ "$kind" == WebSocket ]]; then
        raw="$(curl -sSi --max-time 5 -X GET "$base_url$target" \
          -H "Origin: $origin" -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
          -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' || true)"
      elif [[ "$kind" == REST ]]; then
        raw="$(curl -sSi -X OPTIONS "$base_url$target" -H "Origin: $origin" \
          -H 'Access-Control-Request-Method: GET' -H 'Access-Control-Request-Headers: Content-Type' || true)"
      else
        raw="$(curl -sSi -X GET "$base_url$target" -H "Origin: $origin" || true)"
      fi
      status="$(status_code "$raw")"
      allow="$(header_value "$raw" Access-Control-Allow-Origin)"
      if [[ "$kind" == WebSocket ]]; then
        [[ "$status" == 403 ]] && blocked=true || blocked=false
        if [[ "$known" == true && "$blocked" == true ]]; then ((known_blocked+=1)); fi
        if [[ "$known" == false && "$blocked" == false ]]; then ((other_passed+=1)); fi
        printf '  [%s] Origin: %-32s Status: %-4s -> %s\n' \
          "$known" "$origin" "${status:-(무응답)}" "$blocked"
      else
        [[ "$allow" == "$origin" || "$allow" == '*' ]] && reflected=true || reflected=false
        if [[ "$known" == true && "$reflected" == false ]]; then ((known_blocked+=1)); fi
        if [[ "$known" == false && "$reflected" == true ]]; then ((other_passed+=1)); fi
        printf '  [%s] Origin: %-32s Status: %-4s Allow-Origin: %-20s -> %s\n' \
          "$known" "$origin" "${status:-(무응답)}" "${allow:-(없음)}" "$reflected"
      fi
    done
  done
  if ((other_passed > 0)); then
    printf '  결론: %s CORS가 미등록 오리진도 허용합니다.\n' "$kind"
  elif ((known_blocked > 0)); then
    printf '  결론: %s CORS가 정식 등록 오리진 일부를 차단합니다.\n' "$kind"
  else
    printf '  결론: %s CORS가 등록 오리진만 허용합니다.\n' "$kind"
  fi
}
check_group REST "$path"
check_group WebSocket "$ws_path"
check_group SockJS "$stomp_path"
