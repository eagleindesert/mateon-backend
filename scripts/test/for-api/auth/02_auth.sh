#!/usr/bin/env bash
set -u
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/../00_common.sh"
email="${MATEON_TEST_EMAIL:-}"
password="${MATEON_TEST_PASSWORD:-}"
name="${MATEON_TEST_NAME:-테스트유저}"
user_b_email="${MATEON_USERB_EMAIL:-}"
user_b_password="${MATEON_USERB_PASSWORD:-}"
user_b_name="${MATEON_USERB_NAME:-채팅메이트}"
skip_user_b=false
while (($#)); do
  case "$1" in
    --email|--password|--name|--user-b-email|--user-b-password|--user-b-name)
      (($# >= 2)) || { mateon_color_printf Red '%s\n' "인자 값이 필요합니다: $1" >&2; exit 2; }
      case "$1" in
        --email) email=$2 ;; --password) password=$2 ;; --name) name=$2 ;;
        --user-b-email) user_b_email=$2 ;; --user-b-password) user_b_password=$2 ;;
        --user-b-name) user_b_name=$2 ;;
      esac
      shift 2 ;;
    --skip-user-b) skip_user_b=true; shift ;;
    -h|--help) echo '사용법: 02_auth.sh [--email EMAIL --password PASSWORD] [--skip-user-b]'; exit 0 ;;
    *) mateon_color_printf Red '%s\n' "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
[[ -n "$email" && -n "$password" ]] || { mateon_color_printf Red '%s\n' 'A 계정 이메일과 비밀번호가 필요합니다.' >&2; exit 1; }
mateon_color_printf Magenta '\n########## 2. Auth (인증) ##########\n'

new_account_via_email_verify() {
  local account_email=$1 account_password=$2 account_name=$3 label=$4 save_tokens=$5
  local body code ticket access refresh
  mateon_color_printf Cyan '\n[%s 계정 준비] %s\n' "$label" "$account_email"
  body="$(python3 -c 'import json,sys; print(json.dumps({"email":sys.argv[1]}))' "$account_email")"
  invoke_api --method POST --path /api/auth/email/request --title "$label 이메일 인증코드 요청" --body "$body"
  mateon_color_printf Yellow '\n  %s로 발송된 인증코드 (건너뛰려면 Enter): ' "$account_email"
  IFS= read -r code
  [[ -n "$code" ]] || return 0
  body="$(python3 -c 'import json,sys; print(json.dumps({"email":sys.argv[1],"code":sys.argv[2]}))' "$account_email" "$code")"
  invoke_api --method POST --path /api/auth/email/verify --title "$label 이메일 인증코드 검증" --body "$body"
  ticket="$(json_get "$mateon_response" data.verificationToken)"
  printf '\n'
  [[ -n "$ticket" ]] || return 0
  body="$(python3 - "$account_email" "$account_password" "$account_name" "$ticket" <<'PY'
import json,sys
email,password,name,ticket=sys.argv[1:]
print(json.dumps({"email":email,"password":password,"passwordConfirm":password,"name":name,
 "campus":"JUKJEON","college":"SW융합대학","major":"소프트웨어학과",
 "grade":"3학년","verificationToken":ticket},ensure_ascii=False))
PY
)"
  invoke_api --method POST --path /api/auth/signup --title "$label 회원가입" --body "$body"
  access="$(json_get "$mateon_response" data.accessToken)"
  refresh="$(json_get "$mateon_response" data.refreshToken)"
  printf '\n'
  if [[ -n "$access" && "$save_tokens" == true ]]; then
    save_access_token "$access"
    save_refresh_token "$refresh"
  fi
}

new_account_via_email_verify "$email" "$password" "$name" '2.A' true
body="$(python3 -c 'import json,sys; print(json.dumps({"email":sys.argv[1],"password":sys.argv[2]}))' "$email" "$password")"
invoke_api --method POST --path /api/auth/login --title '2.4 로그인 (A)' --body "$body"
access="$(json_get "$mateon_response" data.accessToken)"
refresh="$(json_get "$mateon_response" data.refreshToken)"
printf '\n'
if [[ -n "$access" ]]; then
  save_access_token "$access"
  save_refresh_token "$refresh"
else
  mateon_color_printf Red '%s\n' '로그인 실패: 인증코드 입력과 회원가입을 확인하세요.' >&2
fi
token="$(get_access_token || true)"
if [[ -n "$token" ]]; then
  sub="$(jwt_subject "$token")"
  [[ "$sub" =~ ^[0-9]+$ ]] && result=true || result=false
  assert_test '2.4b JWT subject가 userId(숫자)' "$result" "sub=$sub"
fi
refresh="$(get_refresh_token || true)"
if [[ -n "$refresh" ]]; then
  body="$(python3 -c 'import json,sys; print(json.dumps({"refreshToken":sys.argv[1]}))' "$refresh")"
  invoke_api --method POST --path /api/auth/token/refresh --title '2.5 토큰 갱신' --body "$body"
  access="$(json_get "$mateon_response" data.accessToken)"
  new_refresh="$(json_get "$mateon_response" data.refreshToken)"
  printf '\n'
  if [[ -n "$access" ]]; then save_access_token "$access"; save_refresh_token "$new_refresh"; fi
fi
token_a="$(get_access_token || true)"

if [[ "$skip_user_b" != true ]]; then
  if [[ "$user_b_email" == "$email" ]]; then
    mateon_color_printf Red '%s\n' '유저 B 이메일이 A와 같아 B 준비를 건너뜁니다.' >&2
  elif [[ -n "$user_b_email" && -n "$user_b_password" ]]; then
    new_account_via_email_verify "$user_b_email" "$user_b_password" "$user_b_name" '2.B' false
    body="$(python3 -c 'import json,sys; print(json.dumps({"email":sys.argv[1],"password":sys.argv[2]}))' "$user_b_email" "$user_b_password")"
    invoke_api --method POST --path /api/auth/login --title '2.B 로그인 확인 (B)' --body "$body"
    access="$(json_get "$mateon_response" data.accessToken)"
    printf '\n'
    [[ -n "$access" ]] && result=true || result=false
    assert_test '2.B 유저 B 로그인 가능(채팅 상대 준비)' "$result" "email=$user_b_email"
  fi
fi
[[ -n "$token_a" ]] && save_access_token "$token_a"
write_test_summary
