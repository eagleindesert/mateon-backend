#!/usr/bin/env bash
set -u
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/../00_common.sh"
kakao_access_token="${MATEON_KAKAO_ACCESS_TOKEN:-}"
while (($#)); do
  case "$1" in
    --kakao-access-token) kakao_access_token=${2:?토큰 값이 필요합니다}; shift 2 ;;
    -h|--help) echo '사용법: 08_social_kakao.sh [--kakao-access-token TOKEN]'; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
printf '\n########## 8. Social Login (Kakao) ##########\n'
invoke_api --method POST --path /api/auth/social/kakao \
  --title '8.1 (invalid) 카카오 로그인 시도 → 차단 기대' \
  --body '{"accessToken":"invalid-token"}'
printf '\n'
invalid_status=$mateon_last_status
if [[ "$invalid_status" =~ ^[0-9]+$ && "$invalid_status" -ge 400 ]]; then result=true; else result=false; fi
assert_test '8.1 잘못된 토큰 카카오 로그인 차단(4xx)' "$result" "status=$invalid_status"

if [[ -z "$kakao_access_token" ]]; then
  echo '[8.2 실제 카카오 로그인] 스킵: 실제 토큰이 없습니다.'
  write_test_summary
  exit $?
fi

body="$(python3 -c 'import json,sys; print(json.dumps({"accessToken":sys.argv[1]}))' "$kakao_access_token")"
invoke_api --method POST --path /api/auth/social/kakao --title '8.2 카카오 로그인/회원가입' --body "$body"
printf '\n'
jwt_access_token="$(json_get "$mateon_response" data.accessToken)"
jwt_refresh_token="$(json_get "$mateon_response" data.refreshToken)"
if [[ -n "$jwt_access_token" ]]; then result=true; else result=false; fi
assert_test '8.2 서비스 JWT 발급(accessToken)' "$result"
if [[ -n "$jwt_access_token" ]]; then
  save_access_token "$jwt_access_token"
  save_refresh_token "$jwt_refresh_token"
  sub="$(jwt_subject "$jwt_access_token")"
  if [[ "$sub" =~ ^[0-9]+$ ]]; then result=true; else result=false; fi
  assert_test '8.2b 서비스 JWT subject가 userId(숫자)' "$result" "sub=$sub"
fi

invoke_api --method GET --path /api/users/me --auth --title '8.3 프로필 schoolVerified 확인'
printf '\n'
verified="$(json_get "$mateon_response" data.schoolVerified)"
if [[ "$verified" == false || -z "$verified" ]]; then result=true; else result=false; fi
assert_test '8.3 신규 카카오 유저 schoolVerified=false' "$result" "schoolVerified=$verified"

team_body="$(python3 - <<'PY'
import datetime, json, random
today = datetime.date.today()
print(json.dumps({"eventId":None,"title":f"카카오 게이팅 테스트 팀 {random.randrange(9999)}",
 "promotionText":"게이팅 검증용 팀","role":["백엔드"],"characteristic":"테스트",
 "capacity":4,"recruitmentStartDate":str(today),"recruitmentEndDate":str(today+datetime.timedelta(days=30))},ensure_ascii=False))
PY
)"
invoke_api --method POST --path /api/teams --auth \
  --title '8.4 (미인증) 팀 생성 시도 → 차단 기대' --body "$team_body"
printf '\n'
gated_status=$mateon_last_status
if [[ "$gated_status" =~ ^[0-9]+$ && "$gated_status" -ge 400 ]]; then result=true; else result=false; fi
assert_test '8.4 미인증 카카오 유저 팀 생성 차단(4xx)' "$result" "status=$gated_status"
write_test_summary
