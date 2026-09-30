#!/usr/bin/env bash
# Bash API 테스트 공통 헬퍼. source 해서 사용한다.
mateon_script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$mateon_script_dir/../../lib/colors.sh"
mateon_env_file="${MATEON_ENV_FILE:-$mateon_script_dir/../.env}"

mateon_load_env() {
  local line key value
  [[ -f "$mateon_env_file" ]] || return 0
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
    [[ "$line" == *=* ]] || continue
    key="${line%%=*}"
    key="${key//[[:space:]]/}"
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    value="${line#*=}"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    if [[ "$value" == \"*\" || "$value" == \'*\' ]]; then value="${value:1:${#value}-2}"; fi
    export "$key=$value"
  done < "$mateon_env_file"
}
mateon_load_env

mateon_base_url="${MATEON_BASE_URL:-http://localhost:8080}"
mateon_token_file="$mateon_script_dir/.auth-token.txt"
mateon_refresh_file="$mateon_script_dir/.refresh-token.txt"
mateon_total=0
mateon_failed=0
mateon_warned=0
mateon_failure_list=()
mateon_warning_list=()
mateon_expected_block_list=()

save_access_token() { [[ -n "${1:-}" ]] && printf '%s\n' "$1" > "$mateon_token_file"; }
get_access_token() { [[ -f "$mateon_token_file" ]] && head -n 1 "$mateon_token_file"; }
save_refresh_token() { [[ -n "${1:-}" ]] && printf '%s\n' "$1" > "$mateon_refresh_file"; }
get_refresh_token() { [[ -f "$mateon_refresh_file" ]] && head -n 1 "$mateon_refresh_file"; }

slot_file() {
  local slot=$1 kind=${2:-auth}
  [[ "$slot" =~ ^[A-Za-z0-9_]+$ ]] || return 2
  printf '%s/.%s-token-%s.txt\n' "$mateon_script_dir" "$kind" "$slot"
}
save_user_slot() {
  local slot=$1 access=${2:-} refresh=${3:-}
  [[ -n "$access" ]] && printf '%s\n' "$access" > "$(slot_file "$slot")"
  [[ -n "$refresh" ]] && printf '%s\n' "$refresh" > "$(slot_file "$slot" refresh)"
  return 0
}
clear_user_slot() {
  local slot=$1
  local auth_file refresh_file
  auth_file="$(slot_file "$slot")" || return 2
  refresh_file="$(slot_file "$slot" refresh)" || return 2
  [[ -f "$auth_file" ]] && : > "$auth_file"
  [[ -f "$refresh_file" ]] && : > "$refresh_file"
}
get_user_slot_token() {
  local file
  file="$(slot_file "$1")" || return 2
  [[ -f "$file" ]] && head -n 1 "$file"
}
use_user() {
  local slot=$1 quiet=${2:-false} token refresh_file
  token="$(get_user_slot_token "$slot")"
  if [[ -z "$token" ]]; then
    mateon_color_printf Red "  (!) 슬롯 '%s'에 저장된 토큰이 없습니다.\n" "$slot" >&2
    return 1
  fi
  save_access_token "$token"
  refresh_file="$(slot_file "$slot" refresh)"
  [[ -f "$refresh_file" ]] && save_refresh_token "$(head -n 1 "$refresh_file")"
  [[ "$quiet" == true ]] || mateon_color_printf DarkCyan '  (i) 활성 유저 전환: %s\n' "$slot"
  return 0
}

json_get() {
  local input=$1 path=$2
  python3 -c '
import json, sys
try:
    value = json.loads(sys.argv[1])
    for key in sys.argv[2].split("."):
        if not key: continue
        value = value[int(key)] if isinstance(value, list) else value[key]
    if value is None: print("")
    elif isinstance(value, bool): print(str(value).lower())
    elif isinstance(value, (dict, list)): print(json.dumps(value, ensure_ascii=False, separators=(",", ":")))
    else: print(value)
except (ValueError, KeyError, IndexError, TypeError):
    print("")
' "$input" "$path"
}

json_ids() {
  python3 -c '
import json,sys
try:
    values=json.loads(sys.argv[1]).get("data") or []
    print(",".join(str(x.get("id", "")) for x in values if isinstance(x,dict)))
except (ValueError,AttributeError,TypeError): print("")
' "$1"
}

json_data_count() {
  python3 -c '
import json,sys
try: print(len(json.loads(sys.argv[1]).get("data") or []))
except (ValueError,AttributeError,TypeError): print(0)
' "$1"
}

json_has_id() {
  local ids=",$(json_ids "$1")," target=$2
  [[ "$ids" == *",$target,"* ]]
}

json_find_by_id() {
  python3 - "$1" "$2" <<'PY'
import json,sys
try:
    data=json.loads(sys.argv[1]).get('data') or []
    print(json.dumps(next((x for x in data if str(x.get('id'))==sys.argv[2]),{}),ensure_ascii=False))
except (ValueError,AttributeError,TypeError): print('{}')
PY
}

json_has_key() {
  python3 - "$1" "$2" <<'PY'
import json,sys
try:
    value=json.loads(sys.argv[1])
    parts=sys.argv[2].split('.')
    for part in parts[:-1]: value=value[part]
    sys.exit(0 if parts[-1] in value else 1)
except (ValueError,KeyError,TypeError): sys.exit(1)
PY
}

json_array_count() {
  python3 - "$1" "$2" <<'PY'
import json,sys
try:
    value=json.loads(sys.argv[1])
    for key in sys.argv[2].split('.'):
        value=value[int(key)] if isinstance(value,list) else value[key]
    print(len(value or []))
except (ValueError,KeyError,IndexError,TypeError): print(0)
PY
}

json_find_field() {
  python3 - "$1" "$2" "$3" "$4" <<'PY'
import json,sys
try:
    value=json.loads(sys.argv[1])
    for key in sys.argv[2].split('.'):
        value=value[int(key)] if isinstance(value,list) else value[key]
    print(json.dumps(next((item for item in value if str(item.get(sys.argv[3]))==sys.argv[4]),{}),ensure_ascii=False))
except (ValueError,KeyError,IndexError,TypeError,AttributeError): print('{}')
PY
}

jwt_subject() {
  local token=${1:-}
  python3 -c '
import base64, json, sys
try:
    p = sys.argv[1].split(".")[1]
    value = json.loads(base64.urlsafe_b64decode(p + "=" * (-len(p) % 4)))
    print(value.get("sub", ""))
except (IndexError, ValueError):
    print("")
' "$token"
}
get_slot_user_id() { jwt_subject "$(get_user_slot_token "$1")"; }

connect_user_slot() {
  local slot=$1 email=${2:-} password=${3:-} token refresh
  if [[ -z "$email" || -z "$password" ]]; then clear_user_slot "$slot"; return 1; fi
  invoke_api --method POST --path /api/auth/login --no-track \
    --body "$(python3 -c 'import json,sys; print(json.dumps({"email":sys.argv[1],"password":sys.argv[2]}))' "$email" "$password")"
  token="$(json_get "$mateon_response" data.accessToken)"
  if [[ -n "$token" ]]; then
    refresh="$(json_get "$mateon_response" data.refreshToken)"
    save_user_slot "$slot" "$token" "$refresh"
    return 0
  fi
  clear_user_slot "$slot"
  return 1
}

# Store presentation metadata only; response bodies and credentials are excluded.
# NUL-delimited fields preserve tabs/newlines when child processes report to the runner.
mateon_record_result() {
  local kind=$1 title=$2 status=${3:-} method=${4:-ASSERT} path=${5:-} detail=${6:-} section=${7:-${MATEON_TEST_SECTION:-}}
  local line="[${status:-NO_RESPONSE}] $title"
  [[ -z "$section" ]] || line="[$section] $line"
  [[ -z "$method$path" ]] || line+="  ($method${path:+ $path})"
  [[ -z "$detail" ]] || line+=" - $detail"
  ((mateon_total+=1))
  case "$kind" in
    FAIL) ((mateon_failed+=1)); mateon_failure_list+=("$line") ;;
    WARN) ((mateon_warned+=1)); mateon_warning_list+=("$line") ;;
    BLOCK) mateon_expected_block_list+=("$line") ;;
  esac
  if [[ -n "${MATEON_RESULTS_FILE:-}" ]]; then
    printf '%s\0' "$kind" "$title" "$status" "$method" "$path" "$detail" "$section" >> "$MATEON_RESULTS_FILE"
  fi
  return 0
}

assert_test() {
  local title=$1 condition=$2 detail=${3:-} warn_only=${4:-false} kind color
  if [[ "$condition" == true ]]; then
    kind=PASS; color=Green
  elif [[ "$warn_only" == true ]]; then
    kind=WARN; color=Yellow
  else
    kind=FAIL; color=Red
  fi
  mateon_record_result "$kind" "$title" "$kind" ASSERT '' "$detail"
  mateon_color_printf "$color" '  [%s] %s %s\n' "$kind" "$title" "$detail"
}

# 응답 본문은 stdout, 진단 및 상태는 stderr로 출력한다.
# 마지막 HTTP 상태는 mateon_last_status에서 읽는다.
invoke_api() {
  local method=GET path= title= body= auth=false no_track=false
  while (($#)); do
    case "$1" in
      --method|--path|--title|--body)
        (($# >= 2)) || { mateon_color_printf Red '%s\n' "인자 값이 필요합니다: $1" >&2; return 2; }
        case "$1" in
          --method) method=$2 ;; --path) path=$2 ;; --title) title=$2 ;; --body) body=$2 ;;
        esac
        shift 2 ;;
      --auth) auth=true; shift ;;
      --no-track) no_track=true; shift ;;
      *) mateon_color_printf Red '%s\n' "알 수 없는 인자: $1" >&2; return 2 ;;
    esac
  done
  [[ -n "$path" ]] || { mateon_color_printf Red '%s\n' '--path가 필요합니다' >&2; return 2; }
  local url="$mateon_base_url$path" raw status response expected=false ok=false token
  local -a args=(-sS -w $'\nHTTP_STATUS:%{http_code}' -X "$method" "$url" -H 'Content-Type: application/json')
  if [[ "$auth" == true ]]; then
    token="$(get_access_token || true)"
    [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  fi
  [[ -n "$body" ]] && args+=(--data-binary "$body")
  if [[ -n "$title" ]]; then
    mateon_color_printf Cyan '\n[%s] %s\n' "$method" "$title" >&2
    mateon_color_printf DarkGray '  -> %s\n' "$url" >&2
  fi
  raw="$(curl "${args[@]}" || true)"
  status="${raw##*HTTP_STATUS:}"
  if [[ "$raw" == *HTTP_STATUS:* ]]; then response="${raw%HTTP_STATUS:*}"; else status=000; response="$raw"; fi
  status="${status//[[:space:]]/}"
  mateon_last_status="$status"
  mateon_response="$response"
  [[ "$title" == *'차단 기대'* ]] && expected=true
  if [[ "$status" =~ ^[0-9]+$ ]]; then
    if [[ "$expected" == true && "$status" -ge 400 ]] ||
       [[ "$expected" == false && "$status" -ge 200 && "$status" -lt 400 ]]; then ok=true; fi
  fi
  if [[ "$ok" == true ]]; then
    mateon_color_printf Green '  Status: %s\n' "$status" >&2
  else
    mateon_color_printf Red '  Status: %s\n' "$status" >&2
  fi
  if [[ "$no_track" == false ]]; then
    local kind=PASS
    if [[ "$ok" == false ]]; then kind=FAIL
    elif [[ "$expected" == true ]]; then kind=BLOCK; fi
    mateon_record_result "$kind" "${title:-$method $path}" "$status" "$method" "$path"
  fi
  printf '%s' "$response"
}

invoke_api_upload() {
  local path= file_path= part_name=image mime=image/png auth=false no_track=false title=
  while (($#)); do
    case "$1" in
      --path|--file-path|--part-name|--mime|--title)
        (($# >= 2)) || return 2
        case "$1" in
          --path) path=$2 ;; --file-path) file_path=$2 ;; --part-name) part_name=$2 ;;
          --mime) mime=$2 ;; --title) title=$2 ;;
        esac
        shift 2 ;;
      --auth) auth=true; shift ;;
      --no-track) no_track=true; shift ;;
      *) mateon_color_printf Red '%s\n' "알 수 없는 인자: $1" >&2; return 2 ;;
    esac
  done
  [[ -f "$file_path" ]] || { mateon_color_printf Red '파일이 없습니다: %s\n' "$file_path" >&2; return 1; }
  local token raw status response expected=false ok=false
  local -a args=(-sS -w $'\nHTTP_STATUS:%{http_code}' -X POST "$mateon_base_url$path"
                 -F "$part_name=@$file_path;type=$mime")
  if [[ "$auth" == true ]]; then
    token="$(get_access_token || true)"
    [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  fi
  if [[ -n "$title" ]]; then
    mateon_color_printf Cyan '\n[POST] %s\n' "$title" >&2
    mateon_color_printf DarkGray '  -> %s\n' "$mateon_base_url$path" >&2
  fi
  raw="$(curl "${args[@]}" || true)"
  status="${raw##*HTTP_STATUS:}"
  if [[ "$raw" == *HTTP_STATUS:* ]]; then response="${raw%HTTP_STATUS:*}"; else status=000; response="$raw"; fi
  status="${status//[[:space:]]/}"
  mateon_last_status="$status"
  mateon_response="$response"
  [[ "$title" == *'차단 기대'* ]] && expected=true
  if [[ "$status" =~ ^[0-9]+$ ]]; then
    if [[ "$expected" == true && "$status" -ge 400 ]] ||
       [[ "$expected" == false && "$status" -ge 200 && "$status" -lt 400 ]]; then ok=true; fi
  fi
  if [[ "$ok" == true ]]; then
    mateon_color_printf Green '  Status: %s\n' "$status" >&2
  else
    mateon_color_printf Red '  Status: %s\n' "$status" >&2
  fi
  if [[ "$no_track" == false ]]; then
    local kind=PASS
    if [[ "$ok" == false ]]; then kind=FAIL
    elif [[ "$expected" == true ]]; then kind=BLOCK; fi
    mateon_record_result "$kind" "${title:-POST $path}" "$status" "POST" "$path"
  fi
  printf '%s' "$response"
}

write_test_summary() {
  local item warn_color=Green fail_color=Green
  ((mateon_warned == 0)) || warn_color=Yellow
  ((mateon_failed == 0)) || fail_color=Red
  mateon_color_printf DarkGray '\n%s\n' '======================================================================'
  mateon_color_printf Magenta ' 테스트 요약 (Test Summary)\n'
  mateon_color_printf DarkGray '%s\n' '======================================================================'
  mateon_color_printf White '  전체: %d\n' "$mateon_total"
  mateon_color_printf Green '  성공: %d\n' "$((mateon_total-mateon_failed-mateon_warned))"
  mateon_color_printf "$warn_color" '  주의: %d\n' "$mateon_warned"
  mateon_color_printf "$fail_color" '  실패: %d\n' "$mateon_failed"
  if ((${#mateon_expected_block_list[@]} > 0)); then
    mateon_color_printf Cyan '\n  정상 차단된 항목 (%d개):\n' "${#mateon_expected_block_list[@]}"
    for item in "${mateon_expected_block_list[@]}"; do mateon_color_printf Cyan '    - %s\n' "$item"; done
  fi
  if ((mateon_warned > 0)); then
    mateon_color_printf Yellow '\n  주의 - 실패로 세지 않았지만 확인이 필요한 항목 (%d개):\n' "$mateon_warned"
    for item in "${mateon_warning_list[@]}"; do mateon_color_printf Yellow '    - %s\n' "$item"; done
  fi
  if ((mateon_failed > 0)); then
    mateon_color_printf Red '\n  실패한 항목:\n'
    for item in "${mateon_failure_list[@]}"; do mateon_color_printf Red '    - %s\n' "$item"; done
  elif ((mateon_warned > 0)); then
    mateon_color_printf Yellow '\n  실패 없음 - 주의 %d건만 확인하세요 🎉\n' "$mateon_warned"
  else
    mateon_color_printf Green '\n  모든 테스트 통과 🎉\n'
  fi
  mateon_color_printf DarkGray '%s\n' '======================================================================='
  return "$((mateon_failed > 255 ? 255 : mateon_failed))"
}

# 16/17 테스트가 각각 독립적으로 사용할 추천 이력을 준비한다.
recommendation_fixture() {
  local number=$1 label=$2 login_body body
  token_a="$(get_access_token || true)"
  [[ -n "$token_a" ]] || { mateon_color_printf Red '%s\n' '먼저 auth/02_auth.sh로 로그인하세요.' >&2; return 1; }
  id_a="$(jwt_subject "$token_a")"
  login_body="$(python3 - "$user_b_email" "$user_b_password" <<'PY'
import json,sys
print(json.dumps({'email':sys.argv[1],'password':sys.argv[2]}))
PY
)"
  invoke_api --method POST --path /api/auth/login --title "$number.0 유저 B 로그인" --body "$login_body" >/dev/null
  token_b="$(json_get "$mateon_response" data.accessToken)"
  [[ -n "$token_b" ]] || { mateon_color_printf Red '%s\n' '유저 B 로그인 실패' >&2; return 1; }
  id_b="$(jwt_subject "$token_b")"
  [[ "$id_a" != "$id_b" ]] || { mateon_color_printf Red '%s\n' 'A/B가 동일 계정입니다.' >&2; return 1; }
  save_access_token "$token_b"
  body="$(python3 - "$label" <<'PY'
import datetime,json,random,sys
today=datetime.date.today()
print(json.dumps(dict(eventId=None,title=f'{sys.argv[1]} BE팀 {random.randrange(9999)}',promotionText='커머스 서비스를 만드는 팀입니다. 주 2회 오프라인으로 모이고 초보자도 환영합니다.',role=['BE'],requiredSkills=['Spring Boot','PostgreSQL'],characteristic='초보 환영',capacity=4,recruitmentStartDate=str(today),recruitmentEndDate=str(today+datetime.timedelta(days=30))),ensure_ascii=False))
PY
)"
  invoke_api --method POST --path /api/teams --auth --title "$number.1 B BE 모집 팀 생성" --body "$body" >/dev/null
  team_id="$(json_get "$mateon_response" data.id)"
  [[ -n "$team_id" ]] || { assert_test "$number.1a 후보 팀 생성 성공" false; return 1; }
  sleep 4
  invoke_api --method POST --path /api/matching/intents/session/restart --auth --title "$number.1b B 의도 세션 초기화" >/dev/null
  invoke_api --method POST --path /api/matching/intents/messages --auth --title "$number.1c B 의도 1턴" --body '{"message":"백엔드 쪽으로 사이드 프로젝트를 하고 싶어요."}' >/dev/null
  invoke_api --method POST --path /api/matching/intents/messages --auth --title "$number.1d B 의도 2턴" --body '{"message":"입문 수준이고 주 2회 오프라인이면 좋겠어요."}' >/dev/null
  save_access_token "$token_a"
  invoke_api --method POST --path /api/matching/intents/session/restart --auth --title "$number.2 A 의도 세션 초기화" >/dev/null
  invoke_api --method POST --path /api/matching/intents/messages --auth --title "$number.2a A 의도 1턴" --body '{"message":"백엔드 공부하려고 포트폴리오용 프로젝트 팀을 찾고 있어요."}' >/dev/null
  invoke_api --method POST --path /api/matching/intents/messages --auth --title "$number.2b A 의도 2턴" --body '{"message":"아직 입문 수준이고, 주 2회 정도 오프라인으로 만나고 싶어요."}' >/dev/null
  [[ "$(json_get "$mateon_response" data.completed)" == true ]] || { mateon_color_printf Red '%s\n' '의도 추출 미완료' >&2; return 1; }
  invoke_api --path '/api/matching/recommendations/user-to-team?limit=200' --auth --title "$number.2c A 팀 추천" >/dev/null
  recommended="$mateon_response"
  target_item="$(python3 - "$recommended" "$team_id" <<'PY'
import json,sys
try:
 items=json.loads(sys.argv[1]).get('data') or []
 print(json.dumps(next((x for x in items if str(x.get('teamId'))==sys.argv[2]),items[0] if items else {}),ensure_ascii=False))
except (ValueError,TypeError,AttributeError): print('{}')
PY
)"
  target_team_id="$(json_get "$target_item" teamId)"
  [[ -n "$target_team_id" ]] || { mateon_color_printf Red '%s\n' '추천 결과가 0건입니다.' >&2; return 1; }
  return 0
}
recommendation_fixture_cleanup() {
  save_access_token "$token_a"
  if [[ "$cleanup" == true && -n "${team_id:-}" ]]; then
    save_access_token "$token_b"
    invoke_api --method DELETE --path "/api/teams/$team_id" --auth --no-track --title "$1.9 B 테스트 팀 삭제" >/dev/null
    save_access_token "$token_a"
  fi
}
