#!/usr/bin/env bash
# 추천 상세 이유, 캐시, 권한 검증.
set -u
source "$(dirname "$0")/00_common.sh"
cleanup=false; user_b_email="${MATEON_USERB_EMAIL:-}"; user_b_password="${MATEON_USERB_PASSWORD:-}"
while (($#)); do
  case "$1" in
    --cleanup) cleanup=true; shift ;;
    --user-b-email) user_b_email=${2:?}; shift 2 ;;
    --user-b-password) user_b_password=${2:?}; shift 2 ;;
    -h|--help) echo '사용법: 16_recommendation_reason.sh [--cleanup] [--user-b-email EMAIL --user-b-password PASSWORD]'; exit 0 ;;
    *) mateon_color_printf Red '%s\n' "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
mateon_color_printf Magenta '\n########## 16. Recommendation Reason ##########\n'
token_a="$(get_access_token || true)"
trap '[[ -n "$token_a" ]] && save_access_token "$token_a"' EXIT
recommendation_fixture 16 이유테스트 || { write_test_summary; exit 1; }
body="$(printf '{"teamId":%s}' "$target_team_id")"
invoke_api --method POST --path /api/matching/recommendations/reason/user-to-team --auth --title '16.3 추천 팀 상세 이유 생성' --body "$body" >/dev/null
reason_1="$(json_get "$mateon_response" data.reason)"
[[ -n "${reason_1//[[:space:]]/}" ]] && ok=true || ok=false
assert_test '16.3a reason 비어 있지 않음' "$ok" "$reason_1"
if [[ "$reason_1" =~ \[stub\#[0-9]+\] ]]; then
  [[ "$reason_1" != *'후보()'* && "$reason_1" != *'대상()'* ]] && ok=true || ok=false
  assert_test '16.3b 스텁에 요약 전달' "$ok"
fi
invoke_api --method POST --path /api/matching/recommendations/reason/user-to-team --auth --title '16.4 이유 재요청 (캐시)' --body "$body" >/dev/null
reason_2="$(json_get "$mateon_response" data.reason)"
[[ "$reason_1" == "$reason_2" ]] && ok=true || ok=false
assert_test '16.4a 재요청 시 같은 문장' "$ok"
invoke_api --method POST --path /api/matching/recommendations/reason/user-to-team --auth --title '16.5 추천 이력 없는 팀 (차단 기대)' --body '{"teamId":999999999}' >/dev/null
invoke_api --method POST --path /api/matching/recommendations/reason/user-to-team --auth --title '16.5b teamId 누락 (차단 기대)' --body '{}' >/dev/null
save_access_token "$token_b"
invoke_api --path "/api/matching/recommendations/team-to-user?teamId=$team_id&limit=200" --auth --title '16.6 B 역방향 추천' >/dev/null
target_user_id="$(json_get "$mateon_response" data.0.userId)"
if [[ -n "$target_user_id" ]]; then
  reverse_body="$(printf '{"teamId":%s,"userId":%s}' "$team_id" "$target_user_id")"
  invoke_api --method POST --path /api/matching/recommendations/reason/team-to-user --auth --title '16.6a 역방향 이유 생성' --body "$reverse_body" >/dev/null
  reverse_1="$(json_get "$mateon_response" data.reason)"
  [[ -n "${reverse_1//[[:space:]]/}" ]] && ok=true || ok=false
  assert_test '16.6b 역방향 reason 비어 있지 않음' "$ok"
  invoke_api --method POST --path /api/matching/recommendations/reason/team-to-user --auth --title '16.6c 역방향 이유 재요청' --body "$reverse_body" >/dev/null
  [[ "$(json_get "$mateon_response" data.reason)" == "$reverse_1" ]] && ok=true || ok=false
  assert_test '16.6d 역방향 캐시 문장 동일' "$ok"
  save_access_token "$token_a"
  invoke_api --method POST --path /api/matching/recommendations/reason/team-to-user --auth --title '16.7 타인 팀 역방향 이유 (차단 기대)' --body "$reverse_body" >/dev/null
else
  mateon_color_printf Yellow '%s\n' '역방향 후보가 0건이라 해당 검증을 건너뜁니다.'
fi
recommendation_fixture_cleanup 16
write_test_summary
