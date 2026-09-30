#!/usr/bin/env bash
# AI 채팅 게이트웨이: 세션, 분류, 위임, 복원, 검증.
set -u
source "$(dirname "$0")/00_common.sh"
strict_routing=false
while (($#)); do
  case "$1" in
    --strict-routing) strict_routing=true; shift ;;
    -h|--help) echo '사용법: 19_ai_gateway.sh [--strict-routing]'; exit 0 ;;
    *) mateon_color_printf Red '%s\n' "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
mateon_color_printf Magenta '\n########## 19. AI Gateway ##########\n'
[[ -n "$(get_access_token || true)" ]] || { mateon_color_printf Red '%s\n' '먼저 auth/02_auth.sh로 로그인하세요.' >&2; exit 1; }
turn_shape() {
  local prefix=$1 turn=$2 expected=$3 domain endpoint matching
  [[ "$(json_get "$turn" data.sessionId)" == "$expected" ]] && ok=true || ok=false
  assert_test "$prefix sessionId 보존" "$ok"
  [[ -n "$(json_get "$turn" data.assistantMessage)" ]] && ok=true || ok=false
  assert_test "$prefix assistantMessage 제공" "$ok"
  domain="$(json_get "$turn" data.domain)"; endpoint="$(json_get "$turn" data.endpoint)"
  if [[ "$domain" == MATCHING_INTENT ]]; then
    [[ -n "$endpoint" ]] && ok=true || ok=false
  else
    [[ -z "$endpoint" ]] && ok=true || ok=false
  fi
  assert_test "$prefix endpoint/domain 일치" "$ok" "domain=$domain endpoint=$endpoint"
  matching="$(json_get "$turn" data.matching)"
  if [[ "$domain" == MATCHING_INTENT ]]; then
    [[ -n "$matching" ]] && ok=true || ok=false
  else
    [[ -z "$matching" ]] && ok=true || ok=false
  fi
  assert_test "$prefix matching 위임 턴에만 제공" "$ok"
}
message_body() {
  python3 - "$1" "$2" <<'PY'
import json,sys
print(json.dumps({'sessionId':int(sys.argv[1]),'message':sys.argv[2]},ensure_ascii=False))
PY
}
session_item() {
  python3 - "$1" "$2" <<'PY'
import json,sys
try: print(json.dumps(next((x for x in json.loads(sys.argv[1]).get('data') or [] if str(x.get('sessionId'))==sys.argv[2]),{}),ensure_ascii=False))
except (ValueError,TypeError,AttributeError): print('{}')
PY
}
invoke_api --method POST --path /api/matching/intents/session/restart --auth --title '19.0 매칭 작업 초기화' >/dev/null
invoke_api --path /api/ai/chat/sessions --auth --no-track --title '19.0b 기존 대화 세션 수' >/dev/null
before_count="$(json_data_count "$mateon_response")"
invoke_api --method POST --path /api/ai/chat/sessions --auth --title '19.1 새 대화 세션 A' >/dev/null
session_a="$(json_get "$mateon_response" data.sessionId)"
[[ "$(json_get "$mateon_response" success)" == true && -n "$session_a" ]] || { assert_test '19.1 대화 세션 생성' false; write_test_summary; exit 1; }
assert_test '19.1a sessionId 발급' true "$session_a"
[[ -z "$(json_get "$mateon_response" data.title)" ]] && ok=true || ok=false
assert_test '19.1b 초기 title=null' "$ok"
[[ -z "$(json_get "$mateon_response" data.lastMessage)" ]] && ok=true || ok=false
assert_test '19.1c 초기 lastMessage=null' "$ok"
invoke_api --path "/api/ai/chat/sessions/$session_a" --auth --title '19.2 빈 세션 복원' >/dev/null
[[ "$(json_array_count "$mateon_response" data.messages)" == 0 ]] && ok=true || ok=false
assert_test '19.2a messages 0건' "$ok"
invoke_api --path /api/ai/chat/sessions --auth --title '19.3 사이드바 목록' >/dev/null
after_count="$(json_data_count "$mateon_response")"
[[ "$(session_item "$mateon_response" "$session_a")" != '{}' ]] && ok=true || ok=false
assert_test '19.3a 새 세션 목록에 포함' "$ok"
((after_count == before_count + 1)) && ok=true || ok=false
assert_test '19.3b 목록 +1' "$ok" "$before_count → $after_count"
[[ "$(json_get "$mateon_response" data.0.sessionId)" == "$session_a" ]] && ok=true || ok=false
assert_test '19.3c 새 세션 맨 앞' "$ok"
invoke_api --method POST --path /api/ai/chat/messages --auth --title '19.4 범위 밖 발화' --body "$(message_body "$session_a" '오늘 서울 날씨 어때?')" >/dev/null
oos="$mateon_response"
[[ "$(json_get "$oos" success)" == true ]] || { assert_test '19.4 발화 전송 성공' false; write_test_summary; exit 1; }
turn_shape 19.4 "$oos" "$session_a"
domain="$(json_get "$oos" data.domain)"
[[ "$domain" != MATCHING_INTENT ]] && router_live=true || router_live=false
[[ "$domain" == OUT_OF_SCOPE ]] && ok=true || ok=false
assert_test '19.4a domain=OUT_OF_SCOPE' "$ok" "domain=$domain"
if [[ "$router_live" == true ]]; then
  [[ "$(json_get "$oos" data.assistantMessage)" =~ \[stub\#[0-9]+\] ]] && ok=true || ok=false
  assert_test '19.4b 스텁 문구' "$ok" '' true
fi
invoke_api --method POST --path /api/ai/chat/messages --auth --title '19.5 내용 없는 발화' --body "$(message_body "$session_a" '안녕하세요')" >/dev/null
unclear="$mateon_response"
if [[ "$(json_get "$unclear" success)" == true ]]; then
  turn_shape 19.5 "$unclear" "$session_a"
  if [[ "$router_live" == true ]]; then
    [[ "$(json_get "$unclear" data.domain)" == UNCLEAR ]] && ok=true || ok=false
    assert_test '19.5a domain=UNCLEAR' "$ok"
  fi
fi
invoke_api --path "/api/ai/chat/sessions/$session_a" --auth --title '19.6 세션 A 복원' >/dev/null
restored="$mateon_response"
[[ "$(json_array_count "$restored" data.messages)" == 4 ]] && ok=true || ok=false
assert_test '19.6a 대화 4건' "$ok"
[[ "$(json_get "$restored" data.messages.0.role)" == USER && "$(json_get "$restored" data.messages.1.role)" == ASSISTANT ]] && ok=true || ok=false
assert_test '19.6b USER/ASSISTANT 시간순' "$ok"
[[ "$(json_get "$restored" data.messages.0.message)" == '오늘 서울 날씨 어때?' ]] && ok=true || ok=false
assert_test '19.6c 사용자 발화 저장' "$ok"
if [[ "$router_live" == true ]]; then
  domains="$(python3 - "$restored" <<'PY'
import json,sys
try: print(sum(x.get('domain') is not None for x in json.loads(sys.argv[1])['data']['messages']))
except (ValueError,KeyError,TypeError): print(-1)
PY
)"
  [[ "$domains" == 0 ]] && ok=true || ok=false
  assert_test '19.6d 게이트웨이 턴 domain=null' "$ok"
fi
invoke_api --path /api/ai/chat/sessions --auth --title '19.7 사이드바 재조회' >/dev/null
mine="$(session_item "$mateon_response" "$session_a")"
[[ "$(json_get "$mine" title)" == '오늘 서울 날씨 어때?' ]] && ok=true || ok=false
assert_test '19.7a 첫 발화 제목' "$ok"
[[ -n "$(json_get "$mine" lastMessage)" ]] && ok=true || ok=false
assert_test '19.7b 마지막 메시지 미리보기' "$ok"
invoke_api --method PUT --path /api/users/me --auth --title '19.8pre 매칭 접두 토글 off' --body '{"matchIncludeProfile":false,"matchIncludePortfolio":false}' >/dev/null
invoke_api --method POST --path /api/ai/chat/sessions --auth --title '19.8 새 대화 세션 B' >/dev/null
session_b="$(json_get "$mateon_response" data.sessionId)"
[[ -n "$session_b" ]] || { assert_test '19.8 세션 B 생성' false; write_test_summary; exit 1; }
invoke_api --method POST --path /api/ai/chat/messages --auth --title '19.8a 매칭 발화' --body "$(message_body "$session_b" '백엔드 개발자인데 같이 공모전 나갈 팀 찾고 있어요')" >/dev/null
match="$mateon_response"
if [[ "$(json_get "$match" success)" != true ]]; then
  assert_test '19.8a 매칭 위임 성공' false
else
  turn_shape 19.8 "$match" "$session_b"
  [[ "$(json_get "$match" data.domain)" == MATCHING_INTENT ]] && ok=true || ok=false
  assert_test '19.8b domain=MATCHING_INTENT' "$ok"
  [[ "$(json_get "$match" data.endpoint)" == /api/matching/intents/messages ]] && ok=true || ok=false
  assert_test '19.8c 매칭 endpoint' "$ok"
  [[ -n "$(json_get "$match" data.matching.sessionId)" ]] && ok=true || ok=false
  assert_test '19.8d matching 포함' "$ok"
  [[ "$(json_get "$match" data.assistantMessage)" == "$(json_get "$match" data.matching.assistantMessage)" ]] && ok=true || ok=false
  assert_test '19.8e 도메인 답변 일치' "$ok"
  [[ -z "$(json_get "$match" data.matching.embeddingVector)" ]] && ok=true || ok=false
  assert_test '19.8f embeddingVector 미노출' "$ok"
  match_completed="$(json_get "$match" data.matching.completed)"
  invoke_api --method POST --path /api/ai/chat/messages --auth --title '19.9 위임 중 범위 밖 발화' --body "$(message_body "$session_b" '그런데 오늘 서울 날씨 어때?')" >/dev/null
  shortcut="$mateon_response"
  if [[ "$(json_get "$shortcut" success)" == true ]]; then
    if [[ "$router_live" == true && "$match_completed" != true ]]; then
      [[ "$(json_get "$shortcut" data.domain)" == MATCHING_INTENT ]] && ok=true || ok=false
      assert_test '19.9a 진행 중 매칭 작업으로 위임' "$ok"
    fi
    [[ "$(json_get "$shortcut" data.sessionId)" == "$session_b" ]] && ok=true || ok=false
    assert_test '19.9b 같은 세션에 이어짐' "$ok"
  fi
fi
invoke_api --method POST --path /api/ai/chat/messages --auth --title '19.10 sessionId 누락 (차단 기대)' --body '{"message":"안녕"}' >/dev/null
invoke_api --method POST --path /api/ai/chat/messages --auth --title '19.10b 빈 메시지 (차단 기대)' --body "$(message_body "$session_a" '')" >/dev/null
invoke_api --method POST --path /api/ai/chat/messages --auth --title '19.10c 없는 세션 (차단 기대)' --body '{"sessionId":99999999,"message":"남의 대화 세션에 쓰기"}' >/dev/null
invoke_api --path /api/ai/chat/sessions/99999999 --auth --title '19.10d 없는 세션 복원 (차단 기대)' >/dev/null
invoke_api --path /api/ai/chat/sessions --title '19.11 인증 없이 목록 (차단 기대)' >/dev/null
invoke_api --method POST --path /api/matching/intents/session/restart --auth --title '19.12 매칭 작업 정리' >/dev/null
if [[ "$router_live" == false ]]; then
  if [[ "$strict_routing" == true ]]; then assert_test '19.x 라우터 분류 수행' false; else mateon_color_printf Yellow '%s\n' '라우터가 매칭으로 폴백했습니다.'; fi
fi
mateon_color_printf DarkGray '대화 세션 A=%s B=%s\n' "$session_a" "$session_b"
write_test_summary
