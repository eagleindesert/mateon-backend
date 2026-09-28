#!/usr/bin/env bash
set -u
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/00_common.sh"
printf '\n########## 18. Bookmark (활동 북마크) ##########\n'

[[ -n "$(get_access_token || true)" ]] || { echo 'accessToken이 없어 북마크 테스트를 건너뜁니다.'; exit 0; }
state_file="$script_dir/.event-ids.json"
[[ -s "$state_file" ]] || { echo '.event-ids.json이 없어 북마크 테스트를 건너뜁니다.'; exit 0; }
target_id="$(python3 - "$state_file" <<'PY'
import json,sys
try:
    data=json.load(open(sys.argv[1]))
    print(data.get('공모전/과학공학') or next((v for k,v in data.items() if k!='__runTag'),''))
except (OSError,ValueError): print('')
PY
)"
run_tag="$(python3 - "$state_file" <<'PY'
import json,sys
try: print(json.load(open(sys.argv[1])).get('__runTag',''))
except (OSError,ValueError): print('')
PY
)"
[[ -n "$target_id" ]] || { echo '대상 활동 ID가 없어 건너뜁니다.'; exit 0; }
printf '  (i) 대상 활동 eventId=%s\n' "$target_id"
path="/api/bookmarks/events/$target_id"
cleanup() { invoke_api --method DELETE --path "$path" --auth --no-track >/dev/null; }
trap cleanup EXIT
cleanup

invoke_api --method POST --path "$path" --title '18.1 비인증 북마크 등록 (차단 기대)'
printf '\n'
invoke_api --method POST --path "$path" --auth --title '18.2 북마크 등록'
printf '\n'
[[ "$(json_get "$mateon_response" data.bookmarked)" == true ]] && result=true || result=false
assert_test '18.2 등록 응답의 bookmarked가 true다' "$result"
[[ "$(json_get "$mateon_response" data.eventId)" == "$target_id" ]] && result=true || result=false
assert_test '18.2 등록 응답에 eventId가 실린다' "$result"

invoke_api --method POST --path "$path" --auth --title '18.3 이미 찜한 활동 재등록'
printf '\n'
[[ "$(json_get "$mateon_response" data.bookmarked)" == true ]] && result=true || result=false
assert_test '18.3 재등록해도 bookmarked=true' "$result"

invoke_api --path '/api/bookmarks/events?page=0&size=100' --auth --title '18.4 내 북마크 목록 조회'
list=$mateon_response
printf '\n'
json_has_id "$list" "$target_id" && result=true || result=false
assert_test '18.4 목록에 방금 찜한 활동이 들어 있다' "$result"
listed="$(json_find_by_id "$list" "$target_id")"
if [[ "$listed" != '{}' ]]; then
  [[ "$(json_get "$listed" bookmarked)" == true ]] && result=true || result=false
  assert_test '18.4 목록 활동은 bookmarked=true' "$result"
  for key in title category field fieldLabel; do
    if python3 -c 'import json,sys; exit(0 if sys.argv[2] in json.loads(sys.argv[1]) else 1)' "$listed" "$key"; then result=true; else result=false; fi
    assert_test "18.4 목록 응답에 $key 필드가 있다" "$result"
  done
fi

invoke_api --path '/api/bookmarks/events/ids' --auth --title '18.5 내 북마크 id 목록 조회'
ids=$mateon_response
printf '\n'
if python3 - "$ids" "$target_id" <<'PY'
import json,sys
try: sys.exit(0 if sys.argv[2] in map(str,json.loads(sys.argv[1]).get('data') or []) else 1)
except (ValueError,AttributeError): sys.exit(1)
PY
then result=true; else result=false; fi
assert_test '18.5 id 목록에 방금 찜한 활동 id가 있다' "$result"

if [[ -n "$run_tag" ]]; then
  search_path="/api/events/search?keyword=$run_tag&size=100"
  invoke_api --path "$search_path" --auth --title '18.6 활동 검색 (로그인) - bookmarked 확인'
  authed=$mateon_response
  printf '\n'
  hit="$(json_find_by_id "$authed" "$target_id")"
  if [[ "$hit" != '{}' ]]; then
    [[ "$(json_get "$hit" bookmarked)" == true ]] && result=true || result=false
    assert_test '18.6 로그인 검색에서 찜한 활동은 bookmarked=true' "$result"
  fi
  other="$(python3 - "$authed" "$target_id" <<'PY'
import json,sys
try:
    data=json.loads(sys.argv[1]).get('data') or []
    print(json.dumps(next((x for x in data if str(x.get('id'))!=sys.argv[2]),{}),ensure_ascii=False))
except (ValueError,AttributeError): print('{}')
PY
)"
  if [[ "$other" != '{}' ]]; then
    [[ "$(json_get "$other" bookmarked)" == false ]] && result=true || result=false
    assert_test '18.6 찜하지 않은 활동은 bookmarked=false' "$result"
  fi
  invoke_api --path "$search_path" --title '18.6 활동 검색 (비로그인)'
  anonymous=$mateon_response
  printf '\n'
  hit="$(json_find_by_id "$anonymous" "$target_id")"
  if [[ "$hit" != '{}' ]]; then
    [[ "$(json_get "$hit" bookmarked)" == false ]] && result=true || result=false
    assert_test '18.6 비로그인 검색에서는 북마크가 노출되지 않는다' "$result"
  fi
fi

invoke_api --method DELETE --path "$path" --auth --title '18.7 북마크 해제'
printf '\n'
[[ "$(json_get "$mateon_response" data.bookmarked)" == false ]] && result=true || result=false
assert_test '18.7 해제 응답의 bookmarked=false' "$result"
invoke_api --path '/api/bookmarks/events?page=0&size=100' --auth --title '18.7 해제 후 목록 재조회'
printf '\n'
json_has_id "$mateon_response" "$target_id" && result=false || result=true
assert_test '18.7 해제한 활동이 목록에서 빠졌다' "$result"
invoke_api --method DELETE --path "$path" --auth --title '18.8 찜한 적 없는 활동 해제'
printf '\n'
[[ "$(json_get "$mateon_response" data.bookmarked)" == false ]] && result=true || result=false
assert_test '18.8 재해제해도 bookmarked=false' "$result"
invoke_api --method POST --path '/api/bookmarks/events/999999999' --auth \
  --title '18.9 없는 활동 북마크 등록 (차단 기대)'
printf '\n'
write_test_summary
