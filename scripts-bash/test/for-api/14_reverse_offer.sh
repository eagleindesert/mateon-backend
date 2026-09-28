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
    -h|--help) echo '사용법: 14_reverse_offer.sh [--cleanup] [--user-b-email EMAIL --user-b-password PASSWORD]'; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
printf '\n########## 14. Reverse Offer (팀→유저) ##########\n'
token_a="$(get_access_token || true)"
[[ -n "$token_a" ]] || { echo 'accessToken이 없습니다.' >&2; exit 1; }
trap 'save_access_token "$token_a"' EXIT
id_a="$(jwt_subject "$token_a")"
login_body="$(python3 -c 'import json,sys; print(json.dumps({"email":sys.argv[1],"password":sys.argv[2]}))' "$user_b_email" "$user_b_password")"
invoke_api --method POST --path /api/auth/login --title '14.0 유저 B 로그인' --body "$login_body"
token_b="$(json_get "$mateon_response" data.accessToken)"
printf '\n'
[[ -n "$token_b" ]] || { echo 'B 로그인 실패' >&2; write_test_summary; exit 1; }
id_b="$(jwt_subject "$token_b")"
[[ "$id_a" != "$id_b" ]] || { echo 'A/B가 동일 계정입니다.' >&2; exit 1; }

team_body() {
  python3 - "$1" "$2" "$3" "$4" "$5" <<'PY'
import datetime,json,sys
title,promotion,skills,characteristic,capacity=sys.argv[1:]
today=datetime.date.today()
print(json.dumps({"eventId":None,"title":title,"promotionText":promotion,"role":["BE"],
 "requiredSkills":skills.split('|'),"characteristic":characteristic,"capacity":int(capacity),
 "recruitmentStartDate":str(today),"recruitmentEndDate":str(today+datetime.timedelta(days=30))},ensure_ascii=False))
PY
}
notification_count() {
  invoke_api --path /api/notifications --auth --no-track >/dev/null
  if [[ -z "${1:-}" ]]; then json_array_count "$mateon_response" data
  else
    python3 - "$mateon_response" "$1" <<'PY'
import json,sys
try: print(sum(1 for item in json.loads(sys.argv[1]).get('data') or [] if item.get('title')==sys.argv[2]))
except (ValueError,AttributeError,TypeError): print(0)
PY
  fi
}

save_access_token "$token_a"
body="$(team_body "역제안테스트 메인팀 $RANDOM" \
  '커머스 서비스를 만드는 팀입니다. 백엔드를 함께할 분을 찾고 있고 초보자도 환영합니다.' \
  'Spring Boot|PostgreSQL' '초보 환영' 2)"
invoke_api --method POST --path /api/teams --auth --title '14.1 A 역제안용 메인팀 생성' --body "$body"
main_id="$(json_get "$mateon_response" data.id)"
printf '\n'
body="$(team_body "역제안테스트 서브팀 $RANDOM" \
  '데이터 파이프라인을 만드는 팀입니다. 백엔드 인원을 모집합니다.' Kafka '차분한 팀' 3)"
invoke_api --method POST --path /api/teams --auth --title '14.2 A 취소 검증용 서브팀 생성' --body "$body"
sub_id="$(json_get "$mateon_response" data.id)"
printf '\n'
if [[ -z "$main_id" || -z "$sub_id" ]]; then
  assert_test '14.2a 팀 2개 생성 성공' false "main=$main_id sub=$sub_id"
  write_test_summary; exit $?
fi
sleep 4

save_access_token "$token_b"
invoke_api --method POST --path /api/matching/intents/session/restart --auth --title '14.3 B 의도 세션 초기화'
printf '\n'
invoke_api --method POST --path /api/matching/intents/messages --auth --title '14.3a B 의도 추출 1턴' \
  --body '{"message":"백엔드 개발을 맡아서 서비스를 만들어보고 싶어요."}'
intent=$mateon_response
printf '\n'
if [[ "$(json_get "$intent" data.completed)" == true ]]; then
  assert_test '14.3b B 1턴 만에 완료 - 2턴 생략' false '실서버에서 1턴 완료' true
else
  invoke_api --method POST --path /api/matching/intents/messages --auth --title '14.3b B 의도 추출 2턴' \
    --body '{"message":"아직 입문 수준이고, 주 2회 정도 오프라인으로 만나고 싶어요."}'
  intent=$mateon_response
  printf '\n'
fi
[[ "$(json_get "$intent" data.completed)" == true ]] && result=true || result=false
assert_test '14.3c B 의도 추출 완료' "$result"
[[ "$result" == true ]] || { write_test_summary; exit $?; }
rec_path="/api/matching/recommendations/team-to-user?teamId=$main_id"
invoke_api --path "$rec_path" --auth --title '14.4 B 남의 팀 역제안 추천 - 차단 기대'
printf '\n'
invoke_api --path "/api/teams/$main_id/offers" --auth --title '14.4a B 남의 팀 제안 목록 - 차단 기대'
printf '\n'

save_access_token "$token_a"
invoke_api --path "$rec_path&limit=10" --auth --title '14.5 A 역제안 추천 요청'
rec=$mateon_response
printf '\n'
count="$(json_data_count "$rec")"
[[ "$(json_get "$rec" success)" == true && "$count" -gt 0 ]] && result=true || result=false
assert_test '14.5a 추천 결과 수신' "$result" "count=$count"
((count > 0)) || { write_test_summary; exit $?; }
if python3 - "$rec" "$id_a" <<'PY'
import json,sys
rows=json.loads(sys.argv[1]).get('data') or []
scores=[float(row['score']) for row in rows]
sys.exit(0 if scores==sorted(scores,reverse=True) else 1)
PY
then result=true; else result=false; fi
assert_test '14.5b 점수 내림차순 정렬' "$result"
if python3 - "$rec" <<'PY'
import json,sys
rows=json.loads(sys.argv[1]).get('data') or []
sys.exit(0 if all(row.get('label') for row in rows) else 1)
PY
then result=true; else result=false; fi
assert_test '14.5c 모든 추천에 label 노출' "$result"
if python3 - "$rec" "$id_a" <<'PY'
import json,sys
rows=json.loads(sys.argv[1]).get('data') or []
sys.exit(0 if all(str(row.get('userId'))!=sys.argv[2] for row in rows) else 1)
PY
then result=true; else result=false; fi
assert_test '14.5d 팀장 본인은 후보에서 제외' "$result"
if python3 - "$rec" <<'PY'
import json,sys
rows=json.loads(sys.argv[1]).get('data') or []
sys.exit(0 if all(not row.get('email') and not row.get('schoolEmail') for row in rows) else 1)
PY
then result=true; else result=false; fi
assert_test '14.5e 추천 응답에 연락처 미노출' "$result"
b_item="$(json_find_field "$rec" data userId "$id_b")"
[[ "$b_item" != '{}' ]] && result=true || result=false
assert_test '14.5f 후보 목록에 B가 포함' "$result"
[[ "$result" == true ]] || { write_test_summary; exit $?; }

save_access_token "$token_b"
notify_b_before="$(notification_count)"
save_access_token "$token_a"
offer_body="$(python3 -c 'import json,sys; print(json.dumps({"userId":int(sys.argv[1]),"message":sys.argv[2]},ensure_ascii=False))' "$id_b" '백엔드 역할로 함께해 주세요!')"
invoke_api --method POST --path "/api/teams/$main_id/offers" --auth --title '14.6 A B에게 제안 발송' --body "$offer_body"
offer=$mateon_response
offer_id="$(json_get "$offer" data.offerId)"
printf '\n'
[[ -n "$offer_id" && "$(json_get "$offer" data.status)" == PENDING ]] && result=true || result=false
assert_test '14.6a 제안 생성됨 (PENDING)' "$result"
if json_has_key "$offer" data.aiScore && [[ -n "$(json_get "$offer" data.aiLabel)" ]]; then result=true; else result=false; fi
assert_test '14.6b AI 점수/근거가 서버에서 스냅샷됨' "$result"
[[ "$(json_get "$offer" data.aiScore)" == "$(json_get "$b_item" score)" ]] && result=true || result=false
assert_test '14.6c 스냅샷 점수가 추천 점수와 일치' "$result"
invoke_api --method POST --path "/api/teams/$main_id/offers" --auth \
  --title '14.6d 같은 유저에게 재제안 - 차단 기대' --body "$offer_body"
printf '\n'
invoke_api --path "$rec_path&limit=10" --auth --title '14.7 제안 후 재추천'
printf '\n'
[[ "$(json_find_field "$mateon_response" data userId "$id_b")" == '{}' ]] && result=true || result=false
assert_test '14.7a 이미 제안한 유저는 후보에서 제외' "$result"
invoke_api --path "/api/teams/$main_id/offers" --auth --title '14.8 A 보낸 제안 목록'
printf '\n'
[[ "$(json_find_field "$mateon_response" data offerId "$offer_id")" != '{}' ]] && result=true || result=false
assert_test '14.8a 보낸 제안 목록에 방금 제안 포함' "$result"

save_access_token "$token_b"
invoke_api --path /api/teams/offers/me --auth --title '14.9 B 받은 제안 목록'
mine="$(json_find_field "$mateon_response" data offerId "$offer_id")"
printf '\n'
if [[ "$mine" != '{}' && "$(json_get "$mine" teamId)" == "$main_id" &&
      -n "$(json_get "$mine" aiLabel)" && -n "$(json_get "$mine" leaderName)" ]]; then result=true; else result=false; fi
assert_test '14.9a 받은 제안 목록에 팀/근거 정보 노출' "$result"
notify_b_after="$(notification_count)"
((notify_b_after > notify_b_before)) && result=true || result=false
assert_test '14.9b 제안 알림이 B에게 도착' "$result"
cancel_before="$(notification_count '제안 취소')"

save_access_token "$token_a"
invoke_api --method POST --path "/api/teams/$sub_id/offers" --auth --title '14.10 A 서브팀에서 B에게 제안' \
  --body "$offer_body"
sub_offer=$mateon_response
sub_offer_id="$(json_get "$sub_offer" data.offerId)"
printf '\n'
[[ -z "$(json_get "$sub_offer" data.aiScore)" ]] && result=true || result=false
assert_test '14.10a 추천을 거치지 않은 제안은 aiScore=null' "$result"
if [[ -n "$sub_offer_id" ]]; then
  invoke_api --method DELETE --path "/api/teams/offers/$sub_offer_id" --auth --title '14.10b A 제안 취소'
  printf '\n'
  invoke_api --path "/api/teams/$sub_id/offers" --auth --title '14.10c A 취소 후 상태 확인'
  canceled="$(json_find_field "$mateon_response" data offerId "$sub_offer_id")"
  printf '\n'
  [[ "$(json_get "$canceled" status)" == CANCELED ]] && result=true || result=false
  assert_test '14.10d 취소된 제안의 status=CANCELED' "$result"
  save_access_token "$token_b"
  invoke_api --method PATCH --path "/api/teams/offers/$sub_offer_id" --auth \
    --title '14.10e B 취소된 제안에 응답 - 차단 기대' --body '{"accepted":true}'
  printf '\n'
  sleep 2
  cancel_after="$(notification_count '제안 취소')"
  ((cancel_after > cancel_before)) && result=true || result=false
  assert_test '14.10f 제안 취소 시 B에게 알림' "$result"
fi

save_access_token "$token_a"
invoke_api --path "/api/teams/$main_id" --auth --title '14.11 A 수락 전 팀 상태'
count_before="$(json_get "$mateon_response" data.currentMemberCount)"
printf '\n'
notify_a_before="$(notification_count)"
save_access_token "$token_b"
invoke_api --method PATCH --path "/api/teams/offers/$offer_id" --auth --title '14.12 B 제안 수락' \
  --body '{"accepted":true}'
printf '\n'
[[ "$(json_get "$mateon_response" data.status)" == ACCEPTED ]] && result=true || result=false
assert_test '14.12a 수락 처리됨 (ACCEPTED)' "$result"
invoke_api --path "/api/teams/$main_id" --auth --title '14.13 B 수락 후 팀 상태'
after_detail=$mateon_response
count_after="$(json_get "$after_detail" data.currentMemberCount)"
printf '\n'
[[ "$count_before" =~ ^[0-9]+$ && "$count_after" == "$((count_before+1))" ]] && result=true || result=false
assert_test '14.13a 수락 즉시 팀원 확정 (인원 +1)' "$result"
[[ "$(json_array_count "$after_detail" data.members)" == "$count_after" ]] && result=true || result=false
assert_test '14.13a-1 제안 수락자가 members에 잡힌다' "$result"
capacity="$(json_get "$after_detail" data.capacity)"
[[ "$capacity" =~ ^[0-9]+$ && "$count_after" -ge "$capacity" ]] && result=true || result=false
assert_test '14.13b 정원이 차면 모집 마감' "$result"
invoke_api --method PATCH --path "/api/teams/offers/$offer_id" --auth \
  --title '14.14 이미 수락한 제안에 재응답 - 차단 기대' --body '{"accepted":false}'
printf '\n'
save_access_token "$token_a"
notify_a_after="$(notification_count)"
((notify_a_after > notify_a_before)) && result=true || result=false
assert_test '14.15 수락 알림이 팀장 A에게 도착' "$result"
invoke_api --method POST --path "/api/teams/$main_id/offers" --auth \
  --title '14.16 마감된 팀에서 제안 - 차단 기대' \
  --body '{"userId":999999999,"message":"마감 후 제안"}'
printf '\n'
if [[ "$cleanup" == true ]]; then
  invoke_api --method DELETE --path "/api/teams/$main_id" --auth --title '14.17 메인팀 삭제'
  printf '\n'
  invoke_api --method DELETE --path "/api/teams/$sub_id" --auth --title '14.17 서브팀 삭제'
  printf '\n'
else
  printf '  (i) A가 만든 팀을 남깁니다: main=%s sub=%s\n' "$main_id" "$sub_id"
fi
write_test_summary
