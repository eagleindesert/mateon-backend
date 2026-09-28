#!/usr/bin/env bash
set -u
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/../00_common.sh"
email_a="${MATEON_TEST_EMAIL:-}"; password_a="${MATEON_TEST_PASSWORD:-}"; name_a="${MATEON_TEST_NAME:-테스트유저}"
email_b="${MATEON_USERB_EMAIL:-}"; password_b="${MATEON_USERB_PASSWORD:-}"; name_b="${MATEON_USERB_NAME:-채팅메이트}"
email_c="${MATEON_USERC_EMAIL:-}"; password_c="${MATEON_USERC_PASSWORD:-}"; name_c="${MATEON_USERC_NAME:-협업메이트}"
force_signup=false; login_only=false
while (($#)); do
  case "$1" in
    --email-a|--password-a|--name-a|--email-b|--password-b|--name-b|--email-c|--password-c|--name-c)
      (($# >= 2)) || exit 2
      case "$1" in
        --email-a) email_a=$2 ;; --password-a) password_a=$2 ;; --name-a) name_a=$2 ;;
        --email-b) email_b=$2 ;; --password-b) password_b=$2 ;; --name-b) name_b=$2 ;;
        --email-c) email_c=$2 ;; --password-c) password_c=$2 ;; --name-c) name_c=$2 ;;
      esac
      shift 2 ;;
    --force-signup) force_signup=true; shift ;;
    --login-only) login_only=true; shift ;;
    -h|--help) echo '사용법: 09_three_users.sh [--login-only|--force-signup] [--email-a ...]'; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
printf '\n########## 9. 유저 3명 준비 ##########\n'
if [[ -z "$email_a" || -z "$email_b" || -z "$email_c" ]]; then
  echo 'MATEON_TEST_EMAIL / MATEON_USERB_EMAIL / MATEON_USERC_EMAIL을 모두 지정하세요.' >&2
  exit 1
fi
if [[ "$email_a" == "$email_b" || "$email_a" == "$email_c" || "$email_b" == "$email_c" ]]; then
  echo '세 유저의 이메일이 서로 달라야 합니다.' >&2
  exit 1
fi

initialize_user_slot() {
  local slot=$1 email=$2 password=$3 name=$4 body code ticket access refresh
  printf '\n[유저 %s] %s\n' "$slot" "$email"
  if [[ "$force_signup" != true ]]; then
    if connect_user_slot "$slot" "$email" "$password"; then
      assert_test "9.$slot 유저 $slot 로그인" true "email=$email (기존 계정)"
      return 0
    fi
  fi
  if [[ "$login_only" == true ]]; then
    assert_test "9.$slot 유저 $slot 로그인" false "email=$email (계정 없음/로그인 실패)"
    return 1
  fi
  body="$(python3 -c 'import json,sys; print(json.dumps({"email":sys.argv[1]}))' "$email")"
  invoke_api --method POST --path /api/auth/email/request --title "9.$slot 이메일 인증코드 요청" --body "$body"
  printf '\n  %s로 발송된 인증코드 (건너뛰려면 Enter): ' "$email"
  IFS= read -r code
  if [[ -z "$code" ]]; then
    clear_user_slot "$slot"
    assert_test "9.$slot 유저 $slot 로그인" false '인증코드 미입력'
    return 1
  fi
  body="$(python3 -c 'import json,sys; print(json.dumps({"email":sys.argv[1],"code":sys.argv[2]}))' "$email" "$code")"
  invoke_api --method POST --path /api/auth/email/verify --title "9.$slot 이메일 인증코드 검증" --body "$body"
  ticket="$(json_get "$mateon_response" data.verificationToken)"
  printf '\n'
  if [[ -z "$ticket" ]]; then
    clear_user_slot "$slot"
    assert_test "9.$slot 유저 $slot 로그인" false '인증 티켓 확보 실패'
    return 1
  fi
  body="$(python3 - "$email" "$password" "$name" "$ticket" <<'PY'
import json,sys
email,password,name,ticket=sys.argv[1:]
print(json.dumps({"email":email,"password":password,"passwordConfirm":password,
 "name":name,"campus":"죽전","college":"SW융합대학","major":"소프트웨어학과",
 "grade":"3학년","verificationToken":ticket},ensure_ascii=False))
PY
)"
  invoke_api --method POST --path /api/auth/signup --title "9.$slot 회원가입" --body "$body"
  access="$(json_get "$mateon_response" data.accessToken)"
  refresh="$(json_get "$mateon_response" data.refreshToken)"
  printf '\n'
  if [[ -z "$access" ]]; then
    body="$(python3 -c 'import json,sys; print(json.dumps({"email":sys.argv[1],"password":sys.argv[2]}))' "$email" "$password")"
    invoke_api --method POST --path /api/auth/login --title "9.$slot 로그인" --body "$body"
    access="$(json_get "$mateon_response" data.accessToken)"
    refresh="$(json_get "$mateon_response" data.refreshToken)"
    printf '\n'
  fi
  if [[ -n "$access" ]]; then
    save_user_slot "$slot" "$access" "$refresh"
    assert_test "9.$slot 유저 $slot 로그인" true "email=$email"
    return 0
  fi
  clear_user_slot "$slot"
  assert_test "9.$slot 유저 $slot 로그인" false "email=$email"
  return 1
}

initialize_user_slot A "$email_a" "$password_a" "$name_a" && ok_a=true || ok_a=false
initialize_user_slot B "$email_b" "$password_b" "$name_b" && ok_b=true || ok_b=false
initialize_user_slot C "$email_c" "$password_c" "$name_c" && ok_c=true || ok_c=false
id_a="$(get_slot_user_id A)"; id_b="$(get_slot_user_id B)"; id_c="$(get_slot_user_id C)"
if [[ "$ok_a" == true && "$ok_b" == true && "$ok_c" == true ]]; then result=true; else result=false; fi
assert_test '9.4 유저 3명 토큰 확보' "$result" "A=$ok_a, B=$ok_b, C=$ok_c"
if [[ -n "$id_a" && -n "$id_b" && -n "$id_c" &&
      "$id_a" != "$id_b" && "$id_a" != "$id_c" && "$id_b" != "$id_c" ]]; then result=true; else result=false; fi
assert_test '9.5 세 유저가 서로 다른 계정' "$result" "userIds=$id_a, $id_b, $id_c"
[[ "$ok_a" == true ]] && use_user A
write_test_summary
