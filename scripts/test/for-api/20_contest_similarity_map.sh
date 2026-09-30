#!/usr/bin/env bash
set -u
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/00_common.sh"
mateon_color_printf Magenta '\n########## 20. 공모전 유사도 지도 ##########\n'
[[ -n "$(get_access_token || true)" ]] || { mateon_color_printf Red '%s\n' 'accessToken이 없습니다.' >&2; exit 1; }
run_tag="$(date +%H%M%S)"
today="$(date +%F)"
labels=('기준' '유사A' '유사B')
titles=("자동테스트 교육 콘텐츠 공모전 $run_tag" "자동테스트 교육 프로그램 공모전 $run_tag" "자동테스트 교육 아이디어 공모전 $run_tag")
descriptions=(
  '대학생이 교육 콘텐츠를 기획하고 제작하는 공모전입니다. 수업 자료와 학습 프로그램을 주제로 합니다.'
  '대학생 대상 교육 프로그램 기획 공모전입니다. 학습 콘텐츠와 수업 자료를 주제로 합니다.'
  '교육 아이디어와 학습 콘텐츠를 제안하는 대학생 공모전입니다. 프로그램 기획이 핵심입니다.'
)
organizers=('메이트온' '메이트온교육' '한국교육재단')
created_ids=()
for i in 0 1 2; do
  body="$(python3 - "${titles[$i]}" "${descriptions[$i]}" "${organizers[$i]}" "$today" <<'PY'
import json,sys
print(json.dumps({"category":"CONTEST","field":"EDUCATION","title":sys.argv[1],
 "description":sys.argv[2],"organizer":sys.argv[3],"startDate":sys.argv[4]},ensure_ascii=False))
PY
)"
  invoke_api --method POST --path /api/events --auth \
    --title "20.1 활동 등록 (${labels[$i]}, 임베딩은 비동기)" --body "$body"
  printf '\n'
  id="$(json_get "$mateon_response" data.id)"
  [[ -n "$id" ]] || { mateon_color_printf Red '%s\n' "등록 응답에 id가 없습니다: ${labels[$i]}" >&2; write_test_summary; exit 1; }
  created_ids+=("$id")
done
query_id=${created_ids[0]}
path="/api/events/$query_id/similarity-map"
map=''
siblings_ready=false
for ((poll=1; poll<=20; poll++)); do
  invoke_api --path "$path" --no-track --title "20.2 유사도 지도 폴링 #$poll"
  printf '\n'
  if [[ "$(json_get "$mateon_response" success)" == true ]]; then
    map=$mateon_response
    if python3 - "$map" "${created_ids[1]}" "${created_ids[2]}" <<'PY'
import json,sys
try:
    ids={str(x['id']) for x in json.loads(sys.argv[1])['data']['points']}
    sys.exit(0 if set(sys.argv[2:]).issubset(ids) else 1)
except (ValueError,KeyError,TypeError): sys.exit(1)
PY
    then
      siblings_ready=true
      invoke_api --path "$path" --title '20.2 유사도 지도 (후보 임베딩 준비됨)'
      map=$mateon_response
      printf '\n'
      break
    fi
  fi
  sleep 2
done
[[ -n "$map" ]] && result=true || result=false
assert_test '20.2a 기준 활동 임베딩이 준비됐다' "$result"
if [[ -n "$map" ]]; then
  [[ "$(json_get "$map" data.query.id)" == "$query_id" ]] && result=true || result=false
  assert_test '20.2b query.id가 기준 eventId와 같다' "$result"
  assert_test '20.2c 이번에 등록한 나머지 2건이 points에 있다' "$siblings_ready"
  if python3 - "$map" "${created_ids[1]}" "${created_ids[2]}" <<'PY'
import json,sys
try:
    data=json.loads(sys.argv[1])['data']
    ids=set(sys.argv[2:])
    points=[p for p in data['points'] if str(p['id']) in ids]
    ok=len(points)==2 and all(all(p.get(k) is not None for k in ('similarity','rankPercentile','radius','x','y')) for p in points)
    sys.exit(0 if ok else 1)
except (ValueError,KeyError,TypeError): sys.exit(1)
PY
  then result=true; else result=false; fi
  assert_test '20.2d 형제 점마다 좌표 필드가 있다' "$result"
  pool="$(json_get "$map" data.candidatePoolTotal)"
  [[ "$pool" =~ ^[0-9]+$ && "$pool" -ge 2 ]] && result=true || result=false
  assert_test '20.2e candidatePoolTotal이 형제 수 이상이다' "$result"
  rings="$(json_get "$map" data.referenceRings)"
  [[ -n "$rings" && "$rings" != '[]' ]] && result=true || result=false
  assert_test '20.2f 후보가 있으면 referenceRings가 비지 않는다' "$result"
  invoke_api --path "$path?topN=10" --title '20.3 유사도 지도 (비로그인)'
  printf '\n'
fi
write_test_summary
