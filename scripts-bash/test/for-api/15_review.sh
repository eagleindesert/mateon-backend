#!/usr/bin/env bash
# 협업 온도 및 팀원 평가 통합 시나리오. auth/09_three_users.sh 선행.
set -u
source "$(dirname "$0")/00_common.sh"
while (($#)); do
  case "$1" in
    --keep-team) shift ;; # 기본 동작도 종료된 팀을 남긴다.
    -h|--help) echo '사용법: 15_review.sh [--keep-team]'; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
printf '\n########## 15. Review (협업 온도) ##########\n'
for slot in A B C; do
  [[ -n "$(get_slot_user_id "$slot")" ]] || { echo "유저 A/B/C 슬롯이 필요합니다. auth/09_three_users.sh를 실행하세요." >&2; exit 1; }
done
id_a="$(get_slot_user_id A)"
id_b="$(get_slot_user_id B)"
id_c="$(get_slot_user_id C)"
printf '유저: A=%s B=%s C=%s\n' "$id_a" "$id_b" "$id_c"
today="$(date +%F)"
end_date="$(date -d '+30 days' +%F)"
use_user A >/dev/null
body="$(python3 - "$today" "$end_date" <<'PY'
import json,random,sys
print(json.dumps(dict(eventId=None,title=f'협업온도 테스트 팀 {random.randrange(9999)}',promotionText='협업 온도 평가 시나리오 검증용',role=['백엔드','프론트엔드'],characteristic='테스트',capacity=3,recruitmentStartDate=sys.argv[1],recruitmentEndDate=sys.argv[2]),ensure_ascii=False))
PY
)"
invoke_api --method POST --path /api/teams --auth --title '15.1 팀 생성 (팀장 A)' --body "$body" >/dev/null
team_id="$(json_get "$mateon_response" data.id)"
[[ -n "$team_id" ]] || { echo '팀 생성 실패' >&2; write_test_summary; exit 1; }
[[ "$(json_get "$mateon_response" data.currentMemberCount)" == 1 ]] && ok=true || ok=false
assert_test '15.1b 생성 직후 인원 = 1' "$ok"
for slot in B C; do
  use_user "$slot" >/dev/null
  body="$(python3 - "$slot" <<'PY'
import json,sys
print(json.dumps(dict(introduction=f'{sys.argv[1]} 지원합니다',message='협업 온도 테스트',contactNumber='010-0000-0000',portfolioUrl='https://github.com/example'),ensure_ascii=False))
PY
)"
  invoke_api --method POST --path "/api/teams/$team_id/apply" --auth --title "15.2 팀 지원 ($slot)" --body "$body" >/dev/null
done
use_user A >/dev/null
invoke_api --path "/api/teams/$team_id/applications" --auth --title '15.3 지원서 목록' >/dev/null
while IFS= read -r app_id; do
  [[ -n "$app_id" ]] || continue
  invoke_api --method PATCH --path "/api/teams/applications/$app_id?isApproved=true" --auth --title "15.3b 지원 승인 ($app_id)" >/dev/null
done < <(python3 - "$mateon_response" <<'PY'
import json,sys
try:
 for x in json.loads(sys.argv[1]).get('data') or []: print(x.get('applicationId',''))
except (ValueError,TypeError,AttributeError): pass
PY
)
invoke_api --path "/api/teams/$team_id" --auth --title '15.4 팀 상세' >/dev/null
[[ "$(json_get "$mateon_response" data.currentMemberCount)" == 3 ]] && ok=true || ok=false
assert_test '15.4b 승인 후 인원 = 3' "$ok"
use_user B >/dev/null
invoke_api --path "/api/teams/$team_id/reviews/targets" --auth --title '15.5 종료 전 평가 대상 조회 (차단 기대)' >/dev/null
review_body="$(printf '{"reviews":[{"revieweeId":%s,"rating":5}]}' "$id_a")"
invoke_api --method POST --path "/api/teams/$team_id/reviews" --auth --title '15.5b 종료 전 평가 제출 (차단 기대)' --body "$review_body" >/dev/null
invoke_api --method POST --path "/api/teams/$team_id/complete" --auth --title '15.6 팀원 B 의 활동 종료 (차단 기대)' >/dev/null
notification_count() {
  invoke_api --path /api/notifications --auth --no-track >/dev/null
  python3 - "$mateon_response" <<'PY'
import json,sys
try: print(sum(x.get('title')=='팀원 평가 요청' for x in json.loads(sys.argv[1]).get('data') or []))
except (ValueError,AttributeError,TypeError): print(0)
PY
}
noti_before="$(notification_count)"
use_user A >/dev/null
invoke_api --method POST --path "/api/teams/$team_id/complete" --auth --title '15.7 활동 종료 (팀장 A)' >/dev/null
[[ "$mateon_last_status" =~ ^2[0-9][0-9]$ ]] && ok=true || ok=false
assert_test '15.7b 활동 종료 성공' "$ok" "status=$mateon_last_status"
invoke_api --method POST --path "/api/teams/$team_id/complete" --auth --title '15.7c 중복 종료 (차단 기대)' >/dev/null
use_user B >/dev/null
sleep 2
noti_after="$(notification_count)"
((noti_after > noti_before)) && ok=true || ok=false
assert_test '15.7d 팀 종료 시 평가 요청 알림 수신' "$ok" "$noti_before → $noti_after"
use_user A >/dev/null
invoke_api --path "/api/teams/$team_id/reviews/targets" --auth --title '15.8 평가 대상 목록' >/dev/null
targets="$mateon_response"
target_ids="$(python3 - "$targets" <<'PY'
import json,sys
try: print(','.join(str(x['userId']) for x in json.loads(sys.argv[1])['data']['targets']))
except (ValueError,KeyError,TypeError): print('')
PY
)"
[[ "$(json_array_count "$targets" data.targets)" == 2 ]] && ok=true || ok=false
assert_test '15.8b 평가 대상은 B, C' "$ok" "targets=$target_ids"
[[ ",$target_ids," != *",$id_a,"* ]] && ok=true || ok=false
assert_test '15.8c 자기 자신 제외' "$ok"
[[ -n "$(json_get "$targets" data.reviewDeadline)" ]] && ok=true || ok=false
assert_test '15.8d 평가 마감 시각 제공' "$ok"
invoke_api --method POST --path "/api/teams/$team_id/reviews" --auth --title '15.9 자기 자신 평가 (차단 기대)' --body "$review_body" >/dev/null
invoke_api --path /api/users/mypage --auth --no-track >/dev/null
before_count="$(json_get "$mateon_response" data.collaborationReviewCount)"
before_temp="$(json_get "$mateon_response" data.collaborationTemperature)"
before_count="${before_count:-0}"
if ((before_count == 0)); then
  python3 - "$before_temp" <<'PY' && ok=true || ok=false
import sys
try: assert abs(float(sys.argv[1])-36.5)<0.05
except (ValueError,AssertionError): sys.exit(1)
PY
  assert_test '15.10a 평가 0건 온도 = 36.5' "$ok" "temperature=$before_temp"
else
  [[ -n "$before_temp" ]] && ok=true || ok=false
  assert_test '15.10a 평가 전 온도 제공' "$ok" "temperature=$before_temp"
fi
for slot in B C; do
  use_user "$slot" >/dev/null
  invoke_api --method POST --path "/api/teams/$team_id/reviews" --auth --title "15.10 평가 제출 ($slot → A)" --body "$review_body" >/dev/null
  [[ "$mateon_last_status" =~ ^2[0-9][0-9]$ ]] && ok=true || ok=false
  assert_test "15.10b $slot 평가 제출 성공" "$ok" "status=$mateon_last_status"
done
use_user B >/dev/null
invoke_api --method POST --path "/api/teams/$team_id/reviews" --auth --title '15.11 중복 평가 (차단 기대)' --body "$(printf '{"reviews":[{"revieweeId":%s,"rating":1}]}' "$id_a")" >/dev/null
invoke_api --path "/api/teams/$team_id/reviews/targets" --auth --title '15.12 제출 후 대상 목록' >/dev/null
entry="$(json_find_field "$mateon_response" data.targets userId "$id_a")"
[[ "$(json_get "$entry" alreadyReviewed)" == true ]] && ok=true || ok=false
assert_test '15.12b 평가 완료 표시' "$ok"
use_user A >/dev/null
invoke_api --path /api/users/mypage --auth --title '15.13 마이페이지 협업 온도' >/dev/null
count="$(json_get "$mateon_response" data.collaborationReviewCount)"
temp="$(json_get "$mateon_response" data.collaborationTemperature)"
[[ "$count" =~ ^[0-9]+$ ]] && ((count == before_count + 2)) && ok=true || ok=false
assert_test '15.13b 평가 수 +2' "$ok" "$before_count → $count"
python3 - "$before_temp" "$temp" <<'PY' && ok=true || ok=false
import sys
try: assert float(sys.argv[2])>float(sys.argv[1])
except (ValueError,AssertionError): sys.exit(1)
PY
assert_test '15.13c 온도 상승' "$ok" "$before_temp → $temp"
if ((before_count == 0)); then
  python3 - "$temp" <<'PY' && ok=true || ok=false
import sys
try: assert abs(float(sys.argv[1])-38.1)<0.05
except (ValueError,AssertionError): sys.exit(1)
PY
  assert_test '15.13d 첫 평가 2건 온도 = 38.1' "$ok" "temperature=$temp"
fi
use_user B >/dev/null
invoke_api --path /api/users/mypage --auth --title '15.14 미평가 유저 온도' >/dev/null
count_b="$(json_get "$mateon_response" data.collaborationReviewCount)"
temp_b="$(json_get "$mateon_response" data.collaborationTemperature)"
[[ -n "$temp_b" ]] && ok=true || ok=false
assert_test '15.14b B 온도 제공' "$ok" "temperature=$temp_b"
if [[ "$count_b" == 0 ]]; then
  python3 - "$temp_b" <<'PY' && ok=true || ok=false
import sys
try: assert abs(float(sys.argv[1])-36.5)<0.05
except (ValueError,AssertionError): sys.exit(1)
PY
  assert_test '15.14c 평가 0건 B 온도 = 36.5' "$ok"
fi
use_user A >/dev/null
printf '종료된 팀 %s 유지. 활성 유저 A.\n' "$team_id"
write_test_summary
