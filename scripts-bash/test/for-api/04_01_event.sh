#!/usr/bin/env bash
set -u
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/00_common.sh"
printf '\n########## 4-1. Event (활동) 조회 ##########\n'
has_token=false
[[ -n "$(get_access_token || true)" ]] && has_token=true
state_file="$script_dir/.event-ids.json"
run_tag=""
declare -A event_ids=()
if [[ -s "$state_file" ]]; then
  while IFS=$'\t' read -r label id; do
    if [[ "$label" == __runTag ]]; then run_tag=$id; else event_ids["$label"]=$id; fi
  done < <(python3 - "$state_file" <<'PY'
import json,sys
try:
    for k,v in json.load(open(sys.argv[1])).items(): print(f"{k}\t{v}")
except (OSError,ValueError): pass
PY
)
  printf '  (i) init이 등록한 활동 %d건을 검증에 사용합니다.\n' "${#event_ids[@]}"
else
  echo '  (i) .event-ids.json 없음: 등록 활동 증분 검증을 건너뜁니다.'
fi

invoke_api --path /api/events/search --title '4.1 활동 검색 (필터 없음)'
anonymous_result=$mateon_response
printf '\n'
invoke_api --path '/api/events/search?category=CONTEST' --title '4.1 활동 검색 (category=CONTEST)'
contest_result=$mateon_response
printf '\n'
sample_id="${event_ids[공모전/과학공학]:-}"
if [[ -n "$sample_id" ]]; then
  sample="$(python3 - "$contest_result" "$sample_id" <<'PY'
import json,sys
try:
    values=json.loads(sys.argv[1]).get('data') or []
    print(json.dumps(next((x for x in values if str(x.get('id'))==sys.argv[2]),{}),ensure_ascii=False))
except (ValueError,AttributeError,TypeError): print('{}')
PY
)"
  if [[ "$sample" != '{}' ]]; then
    [[ "$(json_get "$sample" organizer)" == '단국대 SW중심대학사업단' ]] && result=true || result=false
    assert_test '4.1 응답에 organizer 필드가 있다' "$result"
    [[ "$(json_get "$sample" targetSchool)" == '단국대학교' ]] && result=true || result=false
    assert_test '4.1 응답에 targetSchool 필드가 있다' "$result"
    for key in campusScope targetColleges field fieldLabel; do
      if python3 -c 'import json,sys; exit(0 if sys.argv[2] in json.loads(sys.argv[1]) else 1)' "$sample" "$key"; then result=true; else result=false; fi
      assert_test "4.1 기존 응답 필드 $key가 그대로 남아 있다" "$result"
    done
  fi
fi

invoke_api --path '/api/events/search?field=IT' --title '4.1 활동 검색 (분야 오타 - 차단 기대)'
printf '\n'
if [[ "$has_token" == true ]]; then
  invoke_api --path /api/events/search --auth --title '4.1 활동 검색 (인증 - 순서 동일 기대)'
  authed_result=$mateon_response
  printf '\n'
  [[ "$(json_ids "$anonymous_result")" == "$(json_ids "$authed_result")" ]] && result=true || result=false
  assert_test '4.1 로그인 여부와 무관하게 검색 순서가 같다' "$result"
  invoke_api --path /api/events/recommended --auth --title '4.2 맞춤 활동 추천 (전체 카테고리)'
  printf '\n'
  invoke_api --path '/api/events/recommended?category=EXTERNAL' --auth --title '4.2 맞춤 활동 추천 (category=EXTERNAL)'
  printf '\n'
else
  invoke_api --path /api/events/recommended --title '4.2 맞춤 활동 추천 (비인증 - 실패 확인)'
  printf '\n'
fi
invoke_api --path /api/events --title '4.3 전체 활동 조회 (랜덤)'
printf '\n'

check_limit() {
  local path=$1 title=$2 limit=$3 body count result
  invoke_api --path "$path" --title "$title"
  body=$mateon_response
  printf '\n'
  count="$(json_data_count "$body")"
  ((count <= limit)) && result=true || result=false
  assert_test "$title: ${limit}건 이하" "$result" "count=$count"
}
check_limit /api/events/search '4.4 검색 기본 페이지 (size 미지정)' 20
check_limit '/api/events/search?size=3' '4.4 검색 size=3' 3
check_limit '/api/events/search?size=100000' '4.4 검색 size=100000 (상한 100)' 100
invoke_api --path '/api/events/search?page=0&size=2' --title '4.4 검색 page=0&size=2'
page0=$mateon_response
printf '\n'
invoke_api --path '/api/events/search?page=1&size=2' --title '4.4 검색 page=1&size=2'
page1=$mateon_response
printf '\n'
if [[ "$(json_data_count "$page0")" == 2 ]]; then
  if python3 - "$page0" "$page1" <<'PY'
import json,sys
a={x['id'] for x in json.loads(sys.argv[1])['data']}
b={x['id'] for x in json.loads(sys.argv[2])['data']}
sys.exit(0 if a.isdisjoint(b) else 1)
PY
  then result=true; else result=false; fi
  assert_test '4.4 page=0과 page=1의 활동이 겹치지 않는다' "$result"
fi
check_limit '/api/events?size=5' '4.4 전체 조회 size=5' 5
check_limit '/api/events?size=100000' '4.4 전체 조회 size=100000 (상한 100)' 100

if [[ -n "$run_tag" && ${#event_ids[@]} -gt 0 ]]; then
  invoke_api --path "/api/events/search?keyword=$run_tag&size=100" --title '4.5 키워드 검색 (keyword=runTag)'
  keyword_result=$mateon_response
  printf '\n'
  all_present=true
  for id in "${event_ids[@]}"; do json_has_id "$keyword_result" "$id" || all_present=false; done
  assert_test '4.5 runTag 검색에 등록한 활동이 모두 잡힌다' "$all_present"

  none_keyword="$(python3 -c 'import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))' "${run_tag}_없는키워드")"
  invoke_api --path "/api/events/search?keyword=$none_keyword&size=100" --title '4.5 키워드 검색 (매칭 없음)'
  none_result=$mateon_response
  printf '\n'
  no_leak=true
  for id in "${event_ids[@]}"; do json_has_id "$none_result" "$id" && no_leak=false; done
  assert_test '4.5 매칭 없는 키워드에는 등록한 활동이 안 잡힌다' "$no_leak"

  invoke_api --path "/api/events/search?keyword=$run_tag&category=CONTEST&size=100" --title '4.5 키워드+category'
  filtered=$mateon_response
  printf '\n'
  if [[ -n "${event_ids[공모전/과학공학]:-}" ]]; then
    json_has_id "$filtered" "${event_ids[공모전/과학공학]}" && result=true || result=false
    assert_test '4.5 키워드+CONTEST에 CONTEST 활동이 잡힌다' "$result"
  fi
  if [[ -n "${event_ids[대외활동/과학공학]:-}" ]]; then
    json_has_id "$filtered" "${event_ids[대외활동/과학공학]}" && result=false || result=true
    assert_test '4.5 키워드+CONTEST에서 EXTERNAL 활동은 빠진다' "$result"
  fi

  invoke_api --path '/api/events/search?size=50' --title '4.5 키워드 미지정 (기준)'
  no_keyword=$mateon_response
  printf '\n'
  invoke_api --path '/api/events/search?keyword=&size=50' --title '4.5 빈 키워드'
  empty_keyword=$mateon_response
  printf '\n'
  [[ "$(json_ids "$no_keyword")" == "$(json_ids "$empty_keyword")" ]] && result=true || result=false
  assert_test '4.5 빈 키워드는 미지정과 동일하다' "$result"
  invoke_api --path '/api/events/search?keyword=%EC%A0%84%EC%B2%B4&size=50' --title '4.5 키워드=전체'
  all_keyword=$mateon_response
  printf '\n'
  [[ "$(json_ids "$no_keyword")" == "$(json_ids "$all_keyword")" ]] && result=true || result=false
  assert_test '4.5 키워드=전체는 미지정과 동일하다' "$result"
  check_limit "/api/events/search?keyword=$run_tag&size=2" '4.5 키워드+size=2' 2
  if ((${#event_ids[@]} >= 4)); then
    invoke_api --path "/api/events/search?keyword=$run_tag&page=0&size=2" --title '4.5 키워드+page=0&size=2'
    keyword_page0=$mateon_response
    printf '\n'
    invoke_api --path "/api/events/search?keyword=$run_tag&page=1&size=2" --title '4.5 키워드+page=1&size=2'
    keyword_page1=$mateon_response
    printf '\n'
    if python3 - "$keyword_page0" "$keyword_page1" "$state_file" <<'PY'
import json,sys
a=[x['id'] for x in json.loads(sys.argv[1])['data']]
b=[x['id'] for x in json.loads(sys.argv[2])['data']]
ids=set(json.load(open(sys.argv[3])).values())
ids={x for x in ids if isinstance(x,int)}
sys.exit(0 if set(a).isdisjoint(b) and set(a+b).issubset(ids) else 1)
PY
    then result=true; else result=false; fi
    assert_test '4.5 키워드 페이지가 겹치지 않고 등록 활동만 포함한다' "$result"
  fi
fi
write_test_summary
