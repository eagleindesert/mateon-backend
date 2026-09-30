#!/usr/bin/env bash
set -u
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/00_common.sh"

mateon_color_printf Magenta '\n########## 4-0. Event (활동) 데이터 준비 ##########\n'
run_tag="auto$RANDOM$RANDOM"
mabc_external_id="$run_tag-mabc"
state_file="$script_dir/.event-ids.json"
ids_file="$(mktemp)"
trap 'rm -f "$ids_file"' EXIT
printf '{}\n' > "$ids_file"

if [[ -z "$(get_access_token || true)" ]]; then
  mateon_color_printf Yellow '%s\n' '[4.0 활동 등록] 스킵: 인증이 필요합니다.'
  invoke_api --method POST --path /api/events \
    --title '4.0 활동 등록 (비인증 - 차단 기대)' \
    --body '{"category":"CONTEST","title":"비인증 등록 시도"}'
  printf '\n'
  : > "$state_file"
  write_test_summary
  exit $?
fi

description="$(cat <<'EOF'
[활동 정보]
주최/주관: 업스테이지
기업형태: 중소기업
참여대상: 만 19세 이상 누구나 (개인 또는 최대 3인 팀)
시상규모: 총상금 1,200만 원
활동혜택: 입사시 가산점, 상장 수여
공모분야: 기획/아이디어, 과학/공학
접수링크: https://example.com/apply

코딩 없이 아이디어만으로 만드는 나만의 AI 에이전트. 전국 단위 챌린지입니다.
EOF
)"

seeds=(
  '공모전/과학공학|CONTEST|SCIENCE_ENGINEERING_TECH_IT|단국대학교|단국대 SW중심대학사업단||백엔드/프론트엔드 개발자를 위한 교내 아이디어 공모전입니다.'
  '공모전/디자인|CONTEST|DESIGN_PHOTO_ART_VIDEO|고려대학교|한국디자인진흥원||브랜드 아이덴티티 디자인 공모전입니다.'
  '대외활동/과학공학|EXTERNAL|SCIENCE_ENGINEERING_TECH_IT||과학기술정보통신부||전국 대학생 대상 기술 서포터즈입니다.'
  "MABC/기획아이디어|CONTEST|PLANNING_IDEA||업스테이지|$mabc_external_id|$description"
  "MABC/과학공학|CONTEST|SCIENCE_ENGINEERING_TECH_IT||업스테이지|$mabc_external_id|$description"
)

for seed in "${seeds[@]}"; do
  IFS='|' read -r label category field school organizer external_id description_text <<< "$seed"
  # 설명은 여러 줄일 수 있다. MABC 본문은 구분자 분해 후 공통 변수로 복구한다.
  [[ "$label" == MABC/* ]] && description_text="$description"
  body="$(python3 - "$label" "$category" "$field" "$school" "$organizer" \
    "$external_id" "$description_text" "$run_tag" <<'PY'
import datetime, json, sys
label, category, field, school, organizer, external_id, description, tag = sys.argv[1:]
today = datetime.date.today()
body = {"category":category,"field":field,"title":f"자동테스트 {label} {tag}",
 "description":description,"detailUrl":"https://example.com/contest",
 "startDate":str(today),"endDate":str(today+datetime.timedelta(days=30)),
 "organizer":organizer,"summarizedDescription":label,
 "recommendedTargets":"백엔드 개발자, 프론트엔드 개발자"}
if school: body["targetSchool"] = school
if external_id: body["externalId"] = external_id
print(json.dumps(body, ensure_ascii=False))
PY
)"
  invoke_api --method POST --path /api/events --auth \
    --title "4.0 활동 등록 ($label)" --body "$body"
  printf '\n'
  new_id="$(json_get "$mateon_response" data.id)"
  if [[ -n "$new_id" ]]; then
    python3 - "$ids_file" "$label" "$new_id" <<'PY'
import json, pathlib, sys
p = pathlib.Path(sys.argv[1]); data = json.loads(p.read_text())
data[sys.argv[2]] = int(sys.argv[3]) if sys.argv[3].isdigit() else sys.argv[3]
p.write_text(json.dumps(data, ensure_ascii=False))
PY
    mateon_color_printf Green '  (i) 생성된 eventId = %s (%s)\n' "$new_id" "$label"
  fi
done

python3 - "$ids_file" "$state_file" "$run_tag" <<'PY'
import json, pathlib, sys
data = json.loads(pathlib.Path(sys.argv[1]).read_text())
target = pathlib.Path(sys.argv[2])
if data:
    data["__runTag"] = sys.argv[3]
    target.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
else:
    target.unlink(missing_ok=True)
PY
mateon_color_printf DarkCyan '  (i) 등록한 eventId 저장: %s\n' "$state_file"
write_test_summary
