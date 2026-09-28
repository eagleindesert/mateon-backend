#!/usr/bin/env bash
set -u
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/00_common.sh"
user_b_email="${MATEON_USERB_EMAIL:-}"; user_b_password="${MATEON_USERB_PASSWORD:-}"
while (($#)); do
  case "$1" in
    --user-b-email) user_b_email=${2:?이메일이 필요합니다}; shift 2 ;;
    --user-b-password) user_b_password=${2:?비밀번호가 필요합니다}; shift 2 ;;
    -h|--help) echo '사용법: 05_team.sh [--user-b-email EMAIL --user-b-password PASSWORD]'; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
printf '\n########## 5. Team (팀 모집/지원) ##########\n'
token_a="$(get_access_token || true)"
[[ -n "$token_a" ]] || { echo 'accessToken이 없습니다.' >&2; exit 1; }
trap 'save_access_token "$token_a"' EXIT

team_body() {
  python3 - "$1" "$2" "$3" "$4" "$5" "$6" <<'PY'
import datetime,json,sys
title,promotion,roles,characteristic,capacity,days=sys.argv[1:]
today=datetime.date.today()
print(json.dumps({"eventId":None,"title":title,"promotionText":promotion,"role":roles.split('|'),
 "characteristic":characteristic,"capacity":int(capacity),"recruitmentStartDate":str(today),
 "recruitmentEndDate":str(today+datetime.timedelta(days=int(days)))},ensure_ascii=False))
PY
}
notify_count() {
  invoke_api --path /api/notifications --auth --no-track >/dev/null
  python3 - "$mateon_response" "$1" <<'PY'
import json,sys
try: print(sum(1 for item in json.loads(sys.argv[1]).get('data') or [] if item.get('title')==sys.argv[2]))
except (ValueError,AttributeError,TypeError): print(0)
PY
}

body="$(team_body "자동테스트 팀 모집 $RANDOM" '함께 성장할 팀원을 찾습니다.' '백엔드|프론트엔드' '열정적인 팀' 4 30)"
invoke_api --method POST --path /api/teams --auth --title '5.3 팀 모집글 작성' --body "$body"
team_id="$(json_get "$mateon_response" data.id)"
printf '\n'
invoke_api --path /api/teams --auth --title '5.1 팀 모집글 목록 조회 (전체)'
printf '\n'
invoke_api --path '/api/teams?myPosts=true' --auth --title '5.1 팀 모집글 목록 조회 (내 글만)'
printf '\n'
if [[ -n "$team_id" ]]; then
  invoke_api --path "/api/teams/$team_id" --auth --title '5.2 팀 모집글 상세 조회'
  detail=$mateon_response
  printf '\n'
  for key in members myApplicationStatus isEnded; do
    json_has_key "$detail" "data.$key" && result=true || result=false
    assert_test "5.2 상세 응답에 $key가 있다" "$result"
  done
  members_count="$(json_array_count "$detail" data.members)"
  current_count="$(json_get "$detail" data.currentMemberCount)"
  [[ "$members_count" == "$current_count" ]] && result=true || result=false
  assert_test '5.2d members 수 = currentMemberCount' "$result"
  if python3 - "$detail" <<'PY'
import json,sys
try: sys.exit(0 if any(m.get('isLeader') for m in json.loads(sys.argv[1])['data']['members']) else 1)
except (ValueError,KeyError,TypeError): sys.exit(1)
PY
  then result=true; else result=false; fi
  assert_test '5.2e 명단에 팀장이 isLeader=true로 들어 있다' "$result"
  for pair in 'leader isLeader' 'recruiting isRecruiting'; do
    read -r left right <<< "$pair"
    if json_has_key "$detail" "data.$left" && json_has_key "$detail" "data.$right" &&
       [[ "$(json_get "$detail" "data.$left")" == "$(json_get "$detail" "data.$right")" ]]; then result=true; else result=false; fi
    assert_test "5.2 최상위 $left/$right가 같은 값으로 함께 있다" "$result"
  done
  invoke_api --path "/api/teams/$team_id" --title '5.3 비로그인 상세 조회'
  anon=$mateon_response
  printf '\n'
  [[ "$(json_get "$anon" success)" == true ]] && result=true || result=false
  assert_test '5.3a 토큰 없이도 상세 조회가 된다' "$result"
  [[ "$(json_array_count "$anon" data.members)" == "$(json_get "$anon" data.currentMemberCount)" ]] && result=true || result=false
  assert_test '5.3b 비로그인에서도 members가 온다' "$result"
  if [[ "$(json_get "$anon" data.leader)" != true &&
        "$(json_get "$anon" data.hasApplied)" != true &&
        -z "$(json_get "$anon" data.myApplicationStatus)" ]]; then result=true; else result=false; fi
  assert_test '5.3c 비로그인이면 조회자 기준 필드가 false/null' "$result"
  invoke_api --path /api/teams --title '5.3d 비로그인 목록 조회'
  printf '\n'
  [[ "$(json_get "$mateon_response" success)" == true ]] && result=true || result=false
  assert_test '5.3d 토큰 없이도 목록 조회가 된다' "$result"
  invoke_api --method POST --path /api/teams --title '5.3e 비로그인 작성 - 차단 기대' \
    --body '{"title":"비로그인 작성 시도","capacity":2}'
  printf '\n'
  [[ "$(json_get "$mateon_response" success)" == false ]] && result=true || result=false
  assert_test '5.3e 비로그인 작성은 여전히 차단' "$result"
  invoke_api --path "/api/teams/$team_id/applications" --title '5.3f 비로그인 지원서 목록 - 차단 기대'
  printf '\n'
  [[ "$(json_get "$mateon_response" success)" == false ]] && result=true || result=false
  assert_test '5.3f 비로그인 지원서 목록은 여전히 차단' "$result"
  body="$(team_body '수정된 팀 모집글' '수정된 홍보 문구' '백엔드' '수정된 특징' 3 15)"
  invoke_api --method PUT --path "/api/teams/$team_id" --auth --title '5.4 팀 모집글 수정' --body "$body"
  printf '\n'
fi

login_body="$(python3 -c 'import json,sys; print(json.dumps({"email":sys.argv[1],"password":sys.argv[2]}))' "$user_b_email" "$user_b_password")"
invoke_api --method POST --path /api/auth/login --title '5.6 지원자(B) 로그인' --body "$login_body"
token_b="$(json_get "$mateon_response" data.accessToken)"
printf '\n'
save_access_token "$token_a"
application_id=''
if [[ -n "$team_id" ]]; then
  self_body='{"introduction":"간단 소개입니다.","message":"지원 동기입니다.","contactNumber":"010-1234-5678","portfolioUrl":"https://github.com/example"}'
  invoke_api --method POST --path "/api/teams/$team_id/apply" --auth \
    --title '5.6a 본인 팀 지원 (차단 기대)' --body "$self_body"
  printf '\n'
  [[ "$(json_get "$mateon_response" success)" == false ]] && result=true || result=false
  assert_test '5.6a 본인 팀 지원 차단' "$result"
fi
if [[ -n "$team_id" && -n "$token_b" ]]; then
  before="$(notify_count '지원서 도착')"
  save_access_token "$token_b"
  apply_body='{"introduction":"안녕하세요, 백엔드 지원합니다.","message":"함께 성장하고 싶어 지원합니다.","contactNumber":"010-1234-5678","portfolioUrl":"https://github.com/example"}'
  invoke_api --method POST --path "/api/teams/$team_id/apply" --auth --title '5.6b 팀 지원하기 (B → A 팀)' --body "$apply_body"
  printf '\n'
  [[ "$(json_get "$mateon_response" success)" == true ]] && result=true || result=false
  assert_test '5.6b 타인 팀 지원 성공' "$result"
  invoke_api --method POST --path "/api/teams/$team_id/apply" --auth \
    --title '5.6c 중복 지원 (차단 기대)' --body "$apply_body"
  printf '\n'
  [[ "$(json_get "$mateon_response" success)" == false ]] && result=true || result=false
  assert_test '5.6c 중복 지원 차단' "$result"
  invoke_api --path /api/teams/applications/me --auth --title '5.7 내가 쓴 지원서 목록 (B)'
  my_apps=$mateon_response
  printf '\n'
  mine="$(json_find_field "$my_apps" data teamId "$team_id")"
  application_id="$(json_get "$mine" applicationId)"
  [[ -n "$application_id" ]] && result=true || result=false
  assert_test '5.7 B 지원서 목록에 방금 지원한 팀 포함' "$result"
  if [[ -n "$application_id" ]]; then
    invoke_api --method PUT --path "/api/teams/applications/$application_id" --auth \
      --title '5.10 지원서 수정 (B)' \
      --body '{"introduction":"수정된 소개","message":"수정된 지원 동기","contactNumber":"010-9999-8888","portfolioUrl":"https://github.com/example2"}'
    printf '\n'
  fi
  save_access_token "$token_a"
  sleep 2
  after="$(notify_count '지원서 도착')"
  ((after > before)) && result=true || result=false
  assert_test '5.6d 지원서 제출 시 팀장 A에게 알림 (증분)' "$result" "$before → $after"
fi

if [[ -n "$team_id" ]]; then
  invoke_api --path "/api/teams/$team_id/applications" --auth --title '5.8 내 팀에 온 지원서 목록 (A)'
  team_apps=$mateon_response
  printf '\n'
  count="$(json_array_count "$team_apps" data)"
  ((count >= 1)) && result=true || result=false
  assert_test '5.8 팀장이 B의 지원서를 확인' "$result" "count=$count"
  if ((count >= 1)); then
    [[ -n "$(json_get "$team_apps" data.0.applicant.id)" ]] && result=true || result=false
    assert_test '5.8b 지원자 프로필로 넘어갈 id가 남아 있다' "$result"
    [[ -n "$application_id" ]] || application_id="$(json_get "$team_apps" data.0.applicationId)"
  fi
fi
if [[ -n "$application_id" ]]; then
  invoke_api --path "/api/teams/applications/$application_id" --auth --title '5.12 지원서 상세 조회 (A)'
  printf '\n'
  invoke_api --path "/api/teams/$team_id" --auth --title '5.9 승인 전 팀 상태'
  count_before="$(json_get "$mateon_response" data.currentMemberCount)"
  printf '\n'
  invoke_api --method PATCH --path "/api/teams/applications/$application_id?isApproved=true" --auth \
    --title '5.9 지원서 승인 (A)'
  printf '\n'
  [[ "$(json_get "$mateon_response" success)" == true ]] && result=true || result=false
  assert_test '5.9 팀장 승인 성공' "$result"
  invoke_api --path "/api/teams/$team_id" --auth --title '5.9a 승인 후 팀 상태'
  after_approve=$mateon_response
  count_after="$(json_get "$after_approve" data.currentMemberCount)"
  printf '\n'
  [[ "$count_before" =~ ^[0-9]+$ && "$count_after" == "$((count_before+1))" ]] && result=true || result=false
  assert_test '5.9a 승인 즉시 인원 +1' "$result"
  [[ "$(json_array_count "$after_approve" data.members)" == "$count_after" ]] && result=true || result=false
  assert_test '5.9b 명단 수가 인원 수와 일치' "$result"
  invoke_api --method PATCH --path "/api/teams/applications/$application_id?isApproved=false" --auth \
    --title '5.9c 처리된 지원서 재처리 - 차단 기대'
  printf '\n'
  [[ "$(json_get "$mateon_response" success)" == false ]] && result=true || result=false
  assert_test '5.9c 처리된 지원서 재처리 차단' "$result"
  invoke_api --path "/api/teams/$team_id" --auth --title '5.9d 재처리 후 팀 상태'
  printf '\n'
  [[ "$(json_get "$mateon_response" data.currentMemberCount)" == "$count_after" ]] && result=true || result=false
  assert_test '5.9d 재처리 시도가 인원 수를 건드리지 않음' "$result"
fi

if [[ -n "$token_b" ]]; then
  body="$(team_body "취소·삭제 알림 검증용 팀 $RANDOM" '알림 검증 후 삭제됩니다.' '백엔드' '일회용' 4 30)"
  invoke_api --method POST --path /api/teams --auth --title '5.13 일회용 팀 생성' --body "$body"
  temp_team_id="$(json_get "$mateon_response" data.id)"
  printf '\n'
  if [[ -n "$temp_team_id" ]]; then
    cancel_before="$(notify_count '지원 취소')"
    save_access_token "$token_b"
    invoke_api --method POST --path "/api/teams/$temp_team_id/apply" --auth \
      --title '5.13 B 일회용 팀 지원' --body "$apply_body"
    printf '\n'
    invoke_api --path /api/teams/applications/me --auth --no-track >/dev/null
    temp_app="$(json_find_field "$mateon_response" data teamId "$temp_team_id")"
    temp_app_id="$(json_get "$temp_app" applicationId)"
    if [[ -n "$temp_app_id" ]]; then
      invoke_api --method DELETE --path "/api/teams/applications/$temp_app_id" --auth --title '5.13 B 지원 취소'
      printf '\n'
    fi
    save_access_token "$token_a"
    sleep 2
    cancel_after="$(notify_count '지원 취소')"
    ((cancel_after > cancel_before)) && result=true || result=false
    assert_test '5.13a 지원 취소 시 팀장 A에게 알림' "$result"
    save_access_token "$token_b"
    delete_before="$(notify_count '팀 삭제')"
    invoke_api --method POST --path "/api/teams/$temp_team_id/apply" --auth \
      --title '5.13 B 삭제 검증용 재지원' --body "$apply_body"
    printf '\n'
    save_access_token "$token_a"
    invoke_api --method DELETE --path "/api/teams/$temp_team_id" --auth --title '5.13 A 일회용 팀 삭제'
    printf '\n'
    invoke_api --path "/api/teams/$temp_team_id" --no-track >/dev/null
    [[ "$(json_get "$mateon_response" success)" == false ]] && result=true || result=false
    assert_test '5.13c 삭제된 팀은 더 이상 조회되지 않는다' "$result"
    save_access_token "$token_b"
    sleep 2
    delete_after="$(notify_count '팀 삭제')"
    ((delete_after > delete_before)) && result=true || result=false
    assert_test '5.13b 팀 삭제 시 대기 지원자 B에게 알림' "$result"
    save_access_token "$token_a"
  fi
fi
printf '  (i) 메인 팀은 원본과 같이 남깁니다: teamId=%s\n' "$team_id"
write_test_summary
