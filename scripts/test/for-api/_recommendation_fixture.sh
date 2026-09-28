#!/usr/bin/env bash
# 16/17의 독립적인 추천 이력 준비. 호출자는 00_common.sh를 먼저 source한다.
recommendation_fixture() {
  local number=$1 label=$2 login_body body
  token_a="$(get_access_token || true)"
  [[ -n "$token_a" ]] || { echo '먼저 auth/02_auth.sh로 로그인하세요.' >&2; return 1; }
  id_a="$(jwt_subject "$token_a")"
  login_body="$(python3 - "$user_b_email" "$user_b_password" <<'PY'
import json,sys
print(json.dumps({'email':sys.argv[1],'password':sys.argv[2]}))
PY
)"
  invoke_api --method POST --path /api/auth/login --title "$number.0 유저 B 로그인" --body "$login_body" >/dev/null
  token_b="$(json_get "$mateon_response" data.accessToken)"
  [[ -n "$token_b" ]] || { echo '유저 B 로그인 실패' >&2; return 1; }
  id_b="$(jwt_subject "$token_b")"
  [[ "$id_a" != "$id_b" ]] || { echo 'A/B가 동일 계정입니다.' >&2; return 1; }
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
  [[ "$(json_get "$mateon_response" data.completed)" == true ]] || { echo '의도 추출 미완료' >&2; return 1; }
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
  [[ -n "$target_team_id" ]] || { echo '추천 결과가 0건입니다.' >&2; return 1; }
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
