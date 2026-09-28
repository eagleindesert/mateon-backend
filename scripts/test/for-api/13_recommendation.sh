#!/usr/bin/env bash
set -u
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/00_common.sh"
cleanup=false; user_b_email="${MATEON_USERB_EMAIL:-}"; user_b_password="${MATEON_USERB_PASSWORD:-}"
while (($#)); do
  case "$1" in
    --cleanup) cleanup=true; shift ;;
    --user-b-email) user_b_email=${2:?이메일이 필요합니다}; shift 2 ;;
    --user-b-password) user_b_password=${2:?비밀번호가 필요합니다}; shift 2 ;;
    -h|--help) echo '사용법: 13_recommendation.sh [--cleanup] [--user-b-email EMAIL --user-b-password PASSWORD]'; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
printf '\n########## 13. Recommendation (유저→팀) ##########\n'
token_a="$(get_access_token || true)"
[[ -n "$token_a" ]] || { echo 'accessToken이 없습니다.' >&2; exit 1; }
trap 'save_access_token "$token_a"' EXIT
id_a="$(jwt_subject "$token_a")"
login_body="$(python3 -c 'import json,sys; print(json.dumps({"email":sys.argv[1],"password":sys.argv[2]}))' "$user_b_email" "$user_b_password")"
invoke_api --method POST --path /api/auth/login --title '13.0 유저 B 로그인' --body "$login_body"
token_b="$(json_get "$mateon_response" data.accessToken)"
printf '\n'
[[ -n "$token_b" ]] || { echo 'B 로그인 실패' >&2; write_test_summary; exit 1; }
id_b="$(jwt_subject "$token_b")"
[[ "$id_a" != "$id_b" ]] || { echo 'A/B가 동일 계정입니다.' >&2; exit 1; }
save_access_token "$token_b"
invoke_api --path /api/events --title '13.0b 활동 목록 조회'
linked_id="$(json_get "$mateon_response" data.0.id)"
printf '\n'

make_team() {
  python3 - "$1" "$2" "$3" "$4" "$5" "$6" "$7" <<'PY'
import datetime,json,sys
event_id,title,promotion,role,skills,characteristic,capacity=sys.argv[1:]
today=datetime.date.today()
print(json.dumps({"eventId":int(event_id) if event_id.isdigit() else None,
 "title":title,"promotionText":promotion,"role":[role],"requiredSkills":skills.split('|'),
 "characteristic":characteristic,"capacity":int(capacity),"recruitmentStartDate":str(today),
 "recruitmentEndDate":str(today+datetime.timedelta(days=30))},ensure_ascii=False))
PY
}
body="$(make_team "$linked_id" "추천테스트 BE팀 $RANDOM" \
  '커머스 서비스를 만드는 팀입니다. 주 2회 오프라인으로 모이고 초보자도 환영합니다.' \
  BE 'Spring Boot|PostgreSQL' '초보 환영' 4)"
invoke_api --method POST --path /api/teams --auth --title '13.1 B BE 모집 팀 생성' --body "$body"
team_be_id="$(json_get "$mateon_response" data.id)"
printf '\n'
body="$(make_team '' "추천테스트 FE팀 $RANDOM" \
  '디자인 시스템을 다듬는 팀입니다. 온라인 위주로 모입니다.' FE React '차분한 팀' 3)"
invoke_api --method POST --path /api/teams --auth --title '13.2 B FE 모집 팀 생성' --body "$body"
team_fe_id="$(json_get "$mateon_response" data.id)"
printf '\n'
if [[ -z "$team_be_id" || -z "$team_fe_id" ]]; then
  assert_test '13.2a 후보 팀 2개 생성 성공' false "BE=$team_be_id FE=$team_fe_id"
  write_test_summary
  exit $?
fi
sleep 4
rec_path='/api/matching/recommendations/user-to-team'
invoke_api --path "$rec_path" --auth \
  --title '13.3 B 의도 추출 미완료 추천 요청 - 차단 기대'
printf '\n'
save_access_token "$token_a"
invoke_api --method POST --path /api/matching/intents/session/restart --auth --title '13.4 A 의도 세션 초기화'
printf '\n'
invoke_api --method POST --path /api/matching/intents/messages --auth --title '13.5 A 의도 추출 1턴' \
  --body '{"message":"백엔드 공부하려고 포트폴리오용 프로젝트 팀을 찾고 있어요."}'
intent=$mateon_response
printf '\n'
if [[ "$(json_get "$intent" data.completed)" == true ]]; then
  assert_test '13.6 A 1턴 만에 완료 - 2턴 생략' false '실서버에서 1턴 완료' true
else
  invoke_api --method POST --path /api/matching/intents/messages --auth --title '13.6 A 의도 추출 2턴' \
    --body '{"message":"아직 입문 수준이고, 주 2회 정도 오프라인으로 만나고 싶어요."}'
  intent=$mateon_response
  printf '\n'
fi
[[ "$(json_get "$intent" data.completed)" == true ]] && result=true || result=false
assert_test '13.6a 의도 추출 완료' "$result"
[[ "$result" == true ]] || { write_test_summary; exit $?; }

invoke_api --path "$rec_path?limit=5" --auth --title '13.7 A 팀 추천 요청 (limit=5)'
rec=$mateon_response
printf '\n'
count="$(json_data_count "$rec")"
[[ "$(json_get "$rec" success)" == true && "$count" -gt 0 ]] && result=true || result=false
assert_test '13.7a 추천 결과 수신' "$result" "count=$count"
((count > 0)) || { write_test_summary; exit $?; }
if python3 - "$rec" <<'PY'
import json,sys
rows=json.loads(sys.argv[1]).get('data') or []
scores=[float(row['score']) for row in rows]
sys.exit(0 if scores==sorted(scores,reverse=True) else 1)
PY
then result=true; else result=false; fi
assert_test '13.7b 점수 내림차순 정렬' "$result"
if python3 - "$rec" <<'PY'
import json,sys
sys.exit(0 if all(row.get('label') for row in json.loads(sys.argv[1]).get('data') or []) else 1)
PY
then result=true; else result=false; fi
assert_test '13.7c 모든 추천에 label 노출' "$result"
invoke_api --path "$rec_path?limit=200" --auth --title '13.7-all 넉넉한 limit으로 재조회'
all_items=$mateon_response
printf '\n'
if python3 - "$rec" "$id_a" <<'PY'
import json,sys
rows=json.loads(sys.argv[1]).get('data') or []
sys.exit(0 if all(str(row.get('leaderId'))!=sys.argv[2] for row in rows) else 1)
PY
then result=true; else result=false; fi
assert_test '13.7e 내가 팀장인 팀은 추천에서 제외' "$result"

be_item="$(json_find_field "$all_items" data teamId "$team_be_id")"
fe_item="$(json_find_field "$all_items" data teamId "$team_fe_id")"
if [[ "$be_item" != '{}' ]]; then
  if [[ -n "$linked_id" ]]; then
    [[ -n "$(json_get "$be_item" connectedActivityTitle)" ]] && result=true || result=false
    assert_test '13.7f 활동 연결 팀에 connectedActivityTitle 채워짐' "$result"
  fi
  invoke_api --path "/api/teams/$team_be_id" --auth --title '13.7h 팀 상세 조회'
  printf '\n'
  [[ "$(json_get "$be_item" currentMemberCount)" == "$(json_get "$mateon_response" data.currentMemberCount)" ]] && result=true || result=false
  assert_test '13.7i currentMemberCount가 팀 상세와 일치' "$result"
fi
if [[ "$fe_item" != '{}' ]]; then
  [[ -z "$(json_get "$fe_item" eventId)" && -z "$(json_get "$fe_item" connectedActivityTitle)" ]] && result=true || result=false
  assert_test '13.7g 자율 프로젝트 팀은 활동 정보가 null' "$result"
fi
invoke_api --path "$rec_path?limit=1" --auth --title '13.8 A limit=1 적용 확인'
printf '\n'
[[ "$(json_data_count "$mateon_response")" == 1 ]] && result=true || result=false
assert_test '13.8a limit=1이면 1건만' "$result"
invoke_api --path "$rec_path?eventId=99999999" --auth --title '13.9 없는 eventId - 빈 배열 기대'
printf '\n'
[[ "$(json_get "$mateon_response" success)" == true && "$(json_data_count "$mateon_response")" == 0 ]] && result=true || result=false
assert_test '13.9a 후보 없음 → 200 + 빈 배열' "$result"
if [[ "$cleanup" == true ]]; then
  save_access_token "$token_b"
  invoke_api --method DELETE --path "/api/teams/$team_be_id" --auth --title '13.10 BE팀 삭제'
  printf '\n'
  invoke_api --method DELETE --path "/api/teams/$team_fe_id" --auth --title '13.10 FE팀 삭제'
  printf '\n'
  save_access_token "$token_a"
else
  printf '  (i) B가 만든 팀을 남깁니다: BE=%s FE=%s\n' "$team_be_id" "$team_fe_id"
fi
write_test_summary
