#!/usr/bin/env bash
set -u
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/00_common.sh"
cleanup=false
while (($#)); do
  case "$1" in
    --cleanup) cleanup=true; shift ;;
    -h|--help) echo '사용법: 12_team_embedding.sh [--cleanup]'; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
printf '\n########## 12. Team Embedding ##########\n'
[[ -n "$(get_access_token || true)" ]] || { echo 'accessToken이 없습니다.' >&2; exit 1; }
state_file="$script_dir/.event-ids.json"
linked_event_id=''
if [[ -s "$state_file" ]]; then
  linked_event_id="$(python3 - "$state_file" <<'PY'
import json,sys
try:
    data=json.load(open(sys.argv[1]))
    print(next((v for k,v in data.items() if k!='__runTag'),''))
except (OSError,ValueError): print('')
PY
)"
fi

make_team_body() {
  python3 - "$1" "$2" "$3" "$4" "$5" "$6" "$7" <<'PY'
import datetime,json,sys
event_id,title,promotion,roles,skills,characteristic,days=sys.argv[1:]
today=datetime.date.today()
body={"eventId":int(event_id) if event_id.isdigit() else None,"title":title,
 "promotionText":promotion,"role":roles.split('|'),"characteristic":characteristic,
 "capacity":4 if days=='30' else (3 if days=='15' else 5),
 "recruitmentStartDate":str(today),"recruitmentEndDate":str(today+datetime.timedelta(days=int(days)))}
if skills: body['requiredSkills']=skills.split('|')
print(json.dumps(body,ensure_ascii=False))
PY
}
body="$(make_team_body "$linked_event_id" "임베딩테스트 팀 $RANDOM" \
  '커머스 플랫폼을 만드는 팀입니다. 현재 FE 2명, Design 1명으로 구성돼 있습니다. 매주 화, 목요일 저녁 오프라인으로 모이고, 초보자도 편하게 참여할 수 있는 분위기를 지향합니다. 이번 학기 교내 공모전 수상이 목표입니다.' \
  'BE' 'Spring Boot|PostgreSQL' '초보 환영' 30)"
invoke_api --method POST --path /api/teams --auth --title '12.1 팀 생성 (requiredSkills 포함)' --body "$body"
created=$mateon_response
printf '\n'
if [[ "$(json_get "$created" success)" != true ]]; then
  assert_test '12.1 팀 생성 성공' false "$(json_get "$created" message)"
  write_test_summary
  exit $?
fi
team_id="$(json_get "$created" data.id)"
[[ "$(json_get "$created" data.requiredSkills)" == '["Spring Boot","PostgreSQL"]' ]] && result=true || result=false
assert_test '12.1a 응답에 requiredSkills 노출' "$result"

body="$(make_team_body '' "임베딩테스트 팀(스킬없음) $RANDOM" \
  'requiredSkills 없이도 생성되어야 합니다.' '프론트엔드' '' '테스트' 15)"
invoke_api --method POST --path /api/teams --auth --title '12.2 팀 생성 (requiredSkills 미전송)' --body "$body"
second=$mateon_response
printf '\n'
[[ "$(json_get "$second" success)" == true ]] && result=true || result=false
assert_test '12.2a requiredSkills 없이 생성 성공' "$result"
team_id_2="$(json_get "$second" data.id)"

if [[ -n "$team_id" ]]; then
  body="$(make_team_body '' '임베딩테스트 팀 (수정됨)' \
    '이제 온라인 위주로 모입니다. 대회 입상이 목표입니다.' \
    'BE|Design|데이터마이닝|디자인해요' 'Java|Red-0--is' '빡센 팀' 20)"
  invoke_api --method PUT --path "/api/teams/$team_id" --auth --title '12.3 팀 수정 (임베딩 재계산)' --body "$body"
  updated=$mateon_response
  printf '\n'
  if [[ "$(json_get "$updated" success)" == true &&
        "$(json_get "$updated" data.requiredSkills)" == '["Java","Red-0--is"]' ]]; then result=true; else result=false; fi
  assert_test '12.3a 수정 성공 + requiredSkills 갱신' "$result"
fi
sleep 3
if [[ "$cleanup" == true ]]; then
  printf '  (i) --cleanup이 주어졌지만 원본과 같이 팀 삭제는 비활성화합니다. teamId=%s teamId2=%s\n' "$team_id" "$team_id_2"
else
  printf '  (i) 만든 팀을 남깁니다. teamId=%s teamId2=%s\n' "$team_id" "$team_id_2"
fi
printf '  DB 확인: SELECT embedding_text, activity_goal, missing_fields, vector_dims(embedding) FROM team_embeddings WHERE team_id=%s;\n' "$team_id"
write_test_summary
