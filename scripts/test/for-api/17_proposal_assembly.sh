#!/usr/bin/env bash
# 추천 기반 제안 초안 조립, 발송 및 권한 검증.
set -u
source "$(dirname "$0")/00_common.sh"
source "$(dirname "$0")/_recommendation_fixture.sh"
cleanup=false; user_b_email="${MATEON_USERB_EMAIL:-}"; user_b_password="${MATEON_USERB_PASSWORD:-}"
while (($#)); do
  case "$1" in
    --cleanup) cleanup=true; shift ;;
    --user-b-email) user_b_email=${2:?}; shift 2 ;;
    --user-b-password) user_b_password=${2:?}; shift 2 ;;
    -h|--help) echo '사용법: 17_proposal_assembly.sh [--cleanup] [--user-b-email EMAIL --user-b-password PASSWORD]'; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
printf '\n########## 17. Proposal Assembly ##########\n'
token_a="$(get_access_token || true)"
trap '[[ -n "$token_a" ]] && save_access_token "$token_a"' EXIT
recommendation_fixture 17 제안조립 || { write_test_summary; exit 1; }
expected_score="$(json_get "$target_item" score)"
body="$(printf '{"teamId":%s}' "$target_team_id")"
invoke_api --method POST --path /api/matching/proposals/user-to-team --auth --title '17.3 A 지원 문구 조립' --body "$body" >/dev/null
draft_1="$mateon_response"
summary_1="$(json_get "$draft_1" data.summary)"
message_1="$(json_get "$draft_1" data.message)"
[[ -n "${summary_1//[[:space:]]/}" ]] && ok=true || ok=false
assert_test '17.3a summary 비어 있지 않음' "$ok"
[[ -n "${message_1//[[:space:]]/}" ]] && ok=true || ok=false
assert_test '17.3b message 비어 있지 않음' "$ok"
[[ "$(json_get "$draft_1" data.direction)" == USER_TO_TEAM ]] && ok=true || ok=false
assert_test '17.3c direction=USER_TO_TEAM' "$ok"
[[ "$(json_get "$draft_1" data.synergyScore)" == "$expected_score" ]] && ok=true || ok=false
assert_test '17.3d synergyScore=추천 score' "$ok"
json_has_key "$draft_1" data.portfolioRoleFitScore && ok=false || ok=true
assert_test '17.3e portfolioRoleFitScore 미노출' "$ok"
invoke_api --path /api/teams/applications/me --auth --title '17.3f A 지원서 목록' >/dev/null
apps_before="$(python3 - "$mateon_response" "$target_team_id" <<'PY'
import json,sys
try: print(sum(str(x.get('teamId'))==sys.argv[2] for x in json.loads(sys.argv[1]).get('data') or []))
except (ValueError,TypeError,AttributeError): print(0)
PY
)"
[[ "$apps_before" == 0 ]] && ok=true || ok=false
assert_test '17.3g 조립만으로 지원서가 생기지 않음' "$ok"
invoke_api --method POST --path /api/matching/proposals/user-to-team --auth --title '17.4 A 지원 문구 재조립' --body "$body" >/dev/null
[[ "$(json_get "$mateon_response" data.message)" != "$message_1" ]] && ok=true || ok=false
assert_test '17.4a 매번 새 문구' "$ok"
invoke_api --method POST --path /api/matching/proposals/user-to-team --auth --title '17.5 추천 이력 없는 팀 (차단 기대)' --body '{"teamId":999999999}' >/dev/null
invoke_api --method POST --path /api/matching/proposals/user-to-team --auth --title '17.5b teamId 누락 (차단 기대)' --body '{}' >/dev/null
save_access_token "$token_b"
invoke_api --path "/api/matching/recommendations/team-to-user?teamId=$team_id&limit=200" --auth --title '17.6 B 역방향 추천' >/dev/null
target_user="$(json_get "$mateon_response" data.0)"
target_user_id="$(json_get "$target_user" userId)"
if [[ -n "$target_user_id" ]]; then
  reverse_body="$(printf '{"teamId":%s,"userId":%s}' "$team_id" "$target_user_id")"
  invoke_api --method POST --path /api/matching/proposals/team-to-user --auth --title '17.6a B 역방향 문구 조립' --body "$reverse_body" >/dev/null
  reverse_draft="$mateon_response"
  [[ -n "$(json_get "$reverse_draft" data.summary)" && -n "$(json_get "$reverse_draft" data.message)" ]] && ok=true || ok=false
  assert_test '17.6b 역방향 summary/message 제공' "$ok"
  [[ "$(json_get "$reverse_draft" data.direction)" == TEAM_TO_USER ]] && ok=true || ok=false
  assert_test '17.6c direction=TEAM_TO_USER' "$ok"
  [[ "$(json_get "$reverse_draft" data.synergyScore)" == "$(json_get "$target_user" score)" ]] && ok=true || ok=false
  assert_test '17.6d 역방향 synergyScore=추천 score' "$ok"
  save_access_token "$token_a"
  invoke_api --method POST --path /api/matching/proposals/team-to-user --auth --title '17.7 타인 팀 조립 (차단 기대)' --body "$reverse_body" >/dev/null
else
  echo '역방향 후보가 0건이라 해당 검증을 건너뜁니다.'
fi
save_access_token "$token_a"
if [[ "$target_team_id" == "$team_id" ]]; then
  apply_body="$(python3 - "$summary_1" "$message_1" <<'PY'
import json,sys
print(json.dumps(dict(introduction=sys.argv[1],message=sys.argv[2],contactNumber='010-0000-0000'),ensure_ascii=False))
PY
)"
  invoke_api --method POST --path "/api/teams/$team_id/apply" --auth --title '17.8 조립 문구로 실제 지원' --body "$apply_body" >/dev/null
  [[ "$(json_get "$mateon_response" success)" == true ]] && ok=true || ok=false
  assert_test '17.8a 조립 문구로 지원 성공' "$ok"
  invoke_api --path /api/teams/applications/me --auth --title '17.8b 발송 후 지원서 조회' >/dev/null
  created="$(python3 - "$mateon_response" "$team_id" <<'PY'
import json,sys
try: print(json.dumps(next((x for x in json.loads(sys.argv[1]).get('data') or [] if str(x.get('teamId'))==sys.argv[2]),{}),ensure_ascii=False))
except (ValueError,TypeError,AttributeError): print('{}')
PY
)"
  [[ -n "$(json_get "$created" applicationId)" ]] && ok=true || ok=false
  assert_test '17.8c 지원서 id 채번' "$ok"
  [[ "$(json_get "$created" message)" == "$message_1" ]] && ok=true || ok=false
  assert_test '17.8d 조립 문구 저장' "$ok"
else
  echo '추천 대상이 이번에 만든 팀이 아니어서 중복 지원을 피하고 발송 검증을 건너뜁니다.'
fi
recommendation_fixture_cleanup 17
write_test_summary
