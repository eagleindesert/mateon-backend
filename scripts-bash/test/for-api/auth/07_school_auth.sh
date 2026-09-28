#!/usr/bin/env bash
set -u
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/../00_common.sh"
email="${MATEON_SCHOOL_EMAIL:-${MATEON_TEST_EMAIL:-}}"
while (($#)); do
  case "$1" in
    --email) email=${2:?이메일 값이 필요합니다}; shift 2 ;;
    -h|--help) echo '사용법: 07_school_auth.sh [--email SCHOOL_EMAIL]'; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
printf '\n########## 7. School Email Auth ##########\n'
[[ -n "$(get_access_token || true)" ]] || { echo 'accessToken이 없습니다.' >&2; exit 1; }
[[ -n "$email" ]] || { echo '학교 이메일을 지정하세요.' >&2; exit 1; }

request_body="$(python3 -c 'import json,sys; print(json.dumps({"schoolEmail":sys.argv[1]}))' "$email")"
invoke_api --method POST --path /api/auth/school/email/request --auth \
  --title '7.1 학교 이메일 인증코드 요청' --body "$request_body"
printf '\n'
request_status=$mateon_last_status
if [[ "$request_status" =~ ^2[0-9][0-9]$ ]]; then result=true; else result=false; fi
assert_test '7.1 학교 이메일 코드 요청 성공(2xx)' "$result" "status=$request_status"

printf '\n%s로 발송된 인증코드 (건너뛰려면 Enter): ' "$email"
IFS= read -r school_code
if [[ -z "$school_code" ]]; then
  echo '코드 미입력: verify 이후 단계를 건너뜁니다.'
  write_test_summary
  exit $?
fi
verify_body="$(python3 -c 'import json,sys; print(json.dumps({"schoolEmail":sys.argv[1],"code":sys.argv[2]}))' "$email" "$school_code")"
invoke_api --method POST --path /api/auth/school/email/verify --auth \
  --title '7.2 학교 이메일 인증 검증' --body "$verify_body"
printf '\n'
verify_status=$mateon_last_status
if [[ "$verify_status" =~ ^2[0-9][0-9]$ ]]; then result=true; else result=false; fi
assert_test '7.2 학교 이메일 verify 성공(2xx)' "$result" "status=$verify_status"

invoke_api --method GET --path /api/users/me --auth --title '7.3 프로필 schoolVerified 확인'
printf '\n'
verified="$(json_get "$mateon_response" data.schoolVerified)"
assert_test '7.3 프로필 schoolVerified=true' "$verified" "schoolVerified=$verified"

team_body="$(python3 - <<'PY'
import datetime, json, random
today = datetime.date.today()
print(json.dumps({"eventId":None,"title":f"학교인증 라운드트립 테스트 팀 {random.randrange(9999)}",
 "promotionText":"학교 이메일 인증 검증용 팀","role":["백엔드"],"characteristic":"테스트",
 "capacity":4,"recruitmentStartDate":str(today),"recruitmentEndDate":str(today+datetime.timedelta(days=30))},ensure_ascii=False))
PY
)"
invoke_api --method POST --path /api/teams --auth --title '7.4 (인증 후) 팀 생성 → 허용 기대' --body "$team_body"
printf '\n'
allowed_status=$mateon_last_status
team_id="$(json_get "$mateon_response" data.id)"
if [[ "$allowed_status" =~ ^2[0-9][0-9]$ ]]; then result=true; else result=false; fi
assert_test '7.4 인증 후 팀 생성 허용(2xx)' "$result" "status=$allowed_status, teamId=$team_id"
write_test_summary
