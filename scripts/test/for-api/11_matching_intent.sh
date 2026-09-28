#!/usr/bin/env bash
set -u
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/00_common.sh"
printf '\n########## 11. Matching Intent ##########\n'
[[ -n "$(get_access_token || true)" ]] || { echo 'accessToken이 없습니다.' >&2; exit 1; }
user_id="$(jwt_subject "$(get_access_token)")"
message_path='/api/matching/intents/messages'
session_path='/api/matching/intents/session'

invoke_api --method POST --path "$session_path/restart" --auth --title '11.0 대화 초기화'
printf '\n'
invoke_api --method POST --path "$message_path" --auth --title '11.1 첫 답변 전송' \
  --body '{"message":"같이 프로젝트 할 사람을 찾고 있어요."}'
first=$mateon_response
printf '\n'
if [[ "$(json_get "$first" success)" != true ]]; then
  assert_test '11.1 첫 답변 전송 성공' false "$(json_get "$first" message)"
  write_test_summary
  exit $?
fi
session_id="$(json_get "$first" data.sessionId)"
[[ -n "$session_id" ]] && result=true || result=false
assert_test '11.1a sessionId 발급됨' "$result" "sessionId=$session_id"
[[ -n "$(json_get "$first" data.assistantMessage)" ]] && result=true || result=false
assert_test '11.1b assistantMessage 존재' "$result"
if json_has_key "$first" data.embeddingVector; then result=false; else result=true; fi
assert_test '11.1c embeddingVector가 응답에 없음' "$result"

if [[ "$(json_get "$first" data.completed)" != true ]]; then
  invoke_api --path "$session_path" --auth --title '11.2 세션 복원'
  session=$mateon_response
  printf '\n'
  count="$(json_get "$session" data.messages | python3 -c 'import json,sys
try: print(len(json.load(sys.stdin)))
except ValueError: print(0)')"
  [[ "$count" == 2 ]] && result=true || result=false
  assert_test '11.2a 대화 2건 (USER + ASSISTANT)' "$result"
  [[ "$(json_get "$session" data.messages.0.role)" == USER ]] && result=true || result=false
  assert_test '11.2b 첫 턴이 USER' "$result"
  [[ "$(json_get "$session" data.messages.1.role)" == ASSISTANT ]] && result=true || result=false
  assert_test '11.2c 둘째 턴이 ASSISTANT' "$result"
  [[ "$(json_get "$session" data.status)" == IN_PROGRESS ]] && result=true || result=false
  assert_test '11.2d status=IN_PROGRESS' "$result"
fi

last=$first
declare -A answers=(
  [desired_roles]='백엔드로 참여하고 싶어.'
  [skills]='React 와 TypeScript 를 쓸 줄 알아.'
  [interests]='커머스 쪽에 관심이 있어.'
  [activity_goal]='포트폴리오용 프로젝트를 만들고 싶어.'
  [activity_style]='주 2회 오프라인으로 만나고 싶어.'
  [experience_level]='아직 초보야.'
)
for ((attempt=1; attempt<=6; attempt++)); do
  [[ "$(json_get "$last" data.completed)" == true ]] && break
  field="$(json_get "$last" data.missingFields.0)"
  answer="${answers[$field]:-아직 초보지만 백엔드로 참여해서 포트폴리오용 프로젝트를 하고 싶어.}"
  body="$(python3 -c 'import json,sys; print(json.dumps({"message":sys.argv[1]},ensure_ascii=False))' "$answer")"
  invoke_api --method POST --path "$message_path" --auth \
    --title "11.3 답변 전송 (라운드 $attempt, 항목=$field)" --body "$body"
  last=$mateon_response
  printf '\n'
  if [[ "$(json_get "$last" success)" != true ]]; then
    assert_test '11.3 답변 전송 성공' false "$(json_get "$last" message)"
    write_test_summary
    exit $?
  fi
  [[ "$(json_get "$last" data.sessionId)" == "$session_id" ]] && result=true || result=false
  assert_test "11.3-$attempt 같은 세션에 이어짐" "$result"
done
[[ "$(json_get "$last" data.completed)" == true ]] && result=true || result=false
assert_test '11.3a 추출 완료 (completed=true)' "$result"
[[ "$(json_get "$last" data.missingFields)" == '[]' ]] && result=true || result=false
assert_test '11.3b missingFields 비어있음' "$result"
slot_id="$(json_get "$last" data.slotId)"
[[ -n "$slot_id" ]] && result=true || result=false
assert_test '11.3c slotId 채번됨' "$result" "slotId=$slot_id"
if [[ -n "$(json_get "$last" data.extracted.desiredRoles)" &&
      -n "$(json_get "$last" data.extracted.experienceLevel)" ]]; then result=true; else result=false; fi
assert_test '11.3d extracted가 camelCase로 채워짐' "$result"

invoke_api --path "$session_path" --auth --title '11.4 완료 후 세션 조회'
printf '\n'
[[ "$(json_get "$mateon_response" data)" == '' ]] && result=true || result=false
assert_test '11.4a 진행 중 세션 없음 → data=null' "$result"
invoke_api --method POST --path "$message_path" --auth --title '11.5 완료 후 새 메시지' \
  --body '{"message":"다시 처음부터 할래. 기획으로 참여하고 싶어."}'
third=$mateon_response
printf '\n'
if [[ "$(json_get "$third" success)" == true ]]; then
  [[ "$(json_get "$third" data.sessionId)" != "$session_id" ]] && result=true || result=false
  assert_test '11.5a 새 sessionId 발급' "$result"
fi
invoke_api --method POST --path "$session_path/restart" --auth --title '11.5b 뒷정리'
printf '\n'
invoke_api --method POST --path "$message_path" --auth \
  --title '11.6 빈 메시지 (@NotBlank → 차단 기대)' --body '{"message":""}'
printf '\n'
invoke_api --path "$session_path" --title '11.7 인증 없이 세션 조회 (차단 기대)'
printf '\n'
printf '  원격 DB 참고: SELECT vector_dims(embedding) FROM user_embeddings WHERE user_id=%s;\n' "$user_id"
write_test_summary
