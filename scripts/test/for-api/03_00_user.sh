#!/usr/bin/env bash
set -u
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/00_common.sh"
mateon_color_printf Magenta '\n########## 3. User (사용자) ##########\n'
token="$(get_access_token || true)"
[[ -n "$token" ]] || { mateon_color_printf Red '%s\n' 'accessToken이 없습니다. auth/02_auth.sh를 먼저 실행하세요.' >&2; exit 1; }

invoke_api --path /api/users/me --auth --title '3.1 내 프로필 조회'
profile=$mateon_response
printf '\n'
[[ "$(json_get "$profile" data.schoolVerified)" == true ]] && result=true || result=false
assert_test '3.1b 로컬 유저 schoolVerified=true' "$result"
if json_has_key "$profile" data; then
  if json_has_key "$profile" data.collaborationReviewCount &&
     json_has_key "$profile" data.participatedActivities; then result=true; else result=false; fi
  assert_test '3.1c /me 응답에 reviewCount, participatedActivities 필드 존재' "$result"
  [[ -n "$(json_get "$profile" data.participatedActivities)" ]] && result=true || result=false
  assert_test '3.1c participatedActivities가 null이 아닌 배열이다' "$result"
fi

portfolio_text="사이드 프로젝트 3개를 했습니다. (작성 $(date '+%Y-%m-%d %H:%M:%S'))"
body="$(python3 - "$portfolio_text" <<'PY'
import json,sys
print(json.dumps({"name":"수정된이름","campus":"JUKJEON","college":"SW융합대학",
 "major":"소프트웨어학과","grade":"4학년","interestJobPrimary":"백엔드 개발자",
 "tagline":"안녕하세요, 반갑습니다.","portfolio":sys.argv[1]},ensure_ascii=False))
PY
)"
invoke_api --method PUT --path /api/users/me --auth --title '3.2 내 프로필 수정' --body "$body"
updated=$mateon_response
printf '\n'
json_has_key "$updated" data.participatedActivities && result=true || result=false
assert_test '3.2b 수정 후에도 participatedActivities가 유지된다' "$result"
[[ "$(json_get "$updated" data.portfolio)" == "$portfolio_text" ]] && result=true || result=false
assert_test '3.2c 수정 응답에 방금 보낸 portfolio가 실린다' "$result"
if json_has_key "$updated" data.matchIncludeProfile && json_has_key "$updated" data.matchIncludePortfolio; then result=true; else result=false; fi
assert_test '3.2c2 /me에 매칭 토글 필드가 있다' "$result"

invoke_api --path /api/users/me --auth --title '3.2d 수정 후 내 프로필 재조회'
reread=$mateon_response
printf '\n'
[[ "$(json_get "$reread" data.portfolio)" == "$portfolio_text" ]] && result=true || result=false
assert_test '3.2d GET /me에서 portfolio가 유지된다' "$result"

invoke_api --method PUT --path /api/users/me --auth --title '3.2e portfolio 없이 수정' \
  --body '{"tagline":"부분 수정 확인용 태그라인"}'
partial=$mateon_response
printf '\n'
[[ "$(json_get "$partial" data.portfolio)" == "$portfolio_text" ]] && result=true || result=false
assert_test '3.2e portfolio를 안 보내면 기존 값이 유지된다' "$result"

invoke_api --method PUT --path /api/users/me --auth --title '3.2f 매칭 토글 on' \
  --body '{"matchIncludeProfile":true,"matchIncludePortfolio":true}'
toggled=$mateon_response
printf '\n'
if [[ "$(json_get "$toggled" data.matchIncludeProfile)" == true &&
      "$(json_get "$toggled" data.matchIncludePortfolio)" == true ]]; then result=true; else result=false; fi
assert_test '3.2f 토글을 켜면 true가 실린다' "$result"

invoke_api --method PUT --path /api/users/me --auth --title '3.2g 토글 없이 수정' \
  --body '{"tagline":"토글 유지 확인"}'
kept=$mateon_response
printf '\n'
if [[ "$(json_get "$kept" data.matchIncludeProfile)" == true &&
      "$(json_get "$kept" data.matchIncludePortfolio)" == true ]]; then result=true; else result=false; fi
assert_test '3.2g 토글을 안 보내면 기존 true가 유지된다' "$result"

invoke_api --path /api/users/mypage --auth --title '3.3 마이페이지 조회'
printf '\n'
my_id="$(jwt_subject "$token")"
target_id="$(get_slot_user_id B)"
[[ -n "$target_id" ]] || target_id=$my_id
invoke_api --path "/api/users/$target_id" --auth --title '3.5 타인 프로필 조회'
other=$mateon_response
printf '\n'
if json_has_key "$other" data; then
  [[ "$(json_get "$other" data.userId)" == "$target_id" ]] && result=true || result=false
  assert_test '3.5a 조회한 userId가 그대로 돌아온다' "$result"
  if json_has_key "$other" data.email || json_has_key "$other" data.schoolEmail; then result=false; else result=true; fi
  assert_test '3.5b 공개 프로필에 이메일이 없다' "$result"
  if json_has_key "$other" data.desiredRoles && json_has_key "$other" data.skills &&
     [[ -n "$(json_get "$other" data.desiredRoles)" && -n "$(json_get "$other" data.skills)" ]]; then result=true; else result=false; fi
  assert_test '3.5c 희망역할/스킬이 배열로 내려온다' "$result"
  [[ "$target_id" == "$my_id" ]] && expect_me=true || expect_me=false
  [[ "$(json_get "$other" data.isMe)" == "$expect_me" ]] && result=true || result=false
  assert_test '3.5d isMe가 본인 여부와 일치한다' "$result"
fi
invoke_api --path "/api/users/$my_id" --auth --title '3.5e 자기 프로필을 id로 조회'
mine=$mateon_response
printf '\n'
[[ "$(json_get "$mine" data.isMe)" == true ]] && result=true || result=false
assert_test '3.5e 자기 id 조회는 isMe=true' "$result"
[[ "$(json_get "$mine" data.portfolio)" == "$portfolio_text" ]] && result=true || result=false
assert_test '3.5g 공개 프로필에도 portfolio가 실린다' "$result"
invoke_api --path '/api/users/999999999' --auth \
  --title '3.5f 존재하지 않는 userId - 차단 기대 (404 USER_NOT_FOUND)'
printf '\n'
write_test_summary
