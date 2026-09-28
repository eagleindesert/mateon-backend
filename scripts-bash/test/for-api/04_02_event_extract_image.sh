#!/usr/bin/env bash
set -u
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/00_common.sh"
image_path=''; skip_register=false
while (($#)); do
  case "$1" in
    --image-path) image_path=${2:?이미지 경로가 필요합니다}; shift 2 ;;
    --skip-register) skip_register=true; shift ;;
    -h|--help) echo '사용법: 04_02_event_extract_image.sh [--image-path FILE] [--skip-register]'; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
printf '\n########## 4-2. 공모전 이미지 자동 입력 ##########\n'
run_tag="img$RANDOM$RANDOM"
temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT
if [[ -n "$image_path" ]]; then
  [[ -f "$image_path" ]] || { echo "이미지가 없습니다: $image_path" >&2; exit 1; }
  png_path="$image_path"
else
  png_path="$temp_dir/poster.png"
  printf '%s' 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==' | base64 -d > "$png_path"
fi
mime=image/png
[[ "$png_path" =~ \.[Jj][Pp][Ee]?[Gg]$ ]] && mime=image/jpeg
path='/api/events/extract-image'
invoke_api_upload --path "$path" --file-path "$png_path" --mime "$mime" \
  --title '4.6.1 이미지 추출 (비인증 - 차단 기대)'
printf '\n'
if [[ -z "$(get_access_token || true)" ]]; then write_test_summary; exit $?; fi

invoke_api_upload --path "$path" --file-path "$png_path" --mime "$mime" --auth \
  --title '4.6.2 이미지 추출 (PNG)'
draft_result=$mateon_response
draft="$(json_get "$draft_result" data)"
printf '\n'
if [[ -n "$draft" ]]; then
  keys=(category field title organizer targetSchool startDate endDate detailUrl imageUrl
        description summarizedDescription recommendedTargets externalId)
  for key in "${keys[@]}"; do
    json_has_key "$draft" "$key" && result=true || result=false
    assert_test "4.6.2 초안에 $key 필드가 있다" "$result"
  done
  category="$(json_get "$draft" category)"
  field="$(json_get "$draft" field)"
  case "$category" in CONTEST|EXTERNAL|SCHOOL|ETC) result=true ;; *) result=false ;; esac
  assert_test '4.6.2 category가 허용된 코드값이다' "$result" "category=$category"
  valid_fields=' TRAVEL_HOTEL_AIRLINE PRESS_MEDIA CULTURE_HISTORY EVENT_FESTIVAL EDUCATION DESIGN_PHOTO_ART_VIDEO ECONOMY_FINANCE MANAGEMENT_CONSULTING_MARKETING POLITICS_SOCIETY_LAW SPORTS_FITNESS MEDICAL_HEALTH BEAUTY_COSMETICS SCIENCE_ENGINEERING_TECH_IT COOKING_FOOD STARTUP_SELF_DEVELOPMENT ENVIRONMENT_ENERGY CONTENTS SOCIAL_CONTRIBUTION_EXCHANGE DISTRIBUTION_LOGISTICS PLANNING_IDEA ETC '
  [[ "$valid_fields" == *" $field "* ]] && result=true || result=false
  assert_test '4.6.2 field가 허용된 코드값이다' "$result" "field=$field"
  image_url="$(json_get "$draft" imageUrl)"
  [[ "$image_url" =~ ^https?:// ]] && result=true || result=false
  assert_test '4.6.2 imageUrl이 절대 URL이다' "$result" "imageUrl=$image_url"
  if [[ "$image_url" =~ ^https?:// ]]; then
    probe="$(curl -s -o /dev/null -w '%{http_code} %{content_type}' "$image_url" || true)"
    [[ "$probe" =~ ^200[[:space:]]+image/ ]] && result=true || result=false
    assert_test '4.6.2 업로드된 이미지가 인증 없이 열린다' "$result" "$probe"
  fi
  for date_key in startDate endDate; do
    value="$(json_get "$draft" "$date_key")"
    [[ -z "$value" || "$value" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] && result=true || result=false
    assert_test "4.6.2 $date_key는 yyyy-MM-dd이거나 null이다" "$result" "$value"
  done
fi

cp -- "$png_path" "$temp_dir/invalid.gif"
truncate -s 11M "$temp_dir/huge.png"
invoke_api_upload --path "$path" --file-path "$temp_dir/invalid.gif" --mime image/gif --auth \
  --title '4.6.3 gif 업로드 (차단 기대)'
printf '\n'
invoke_api_upload --path "$path" --file-path "$png_path" --mime "$mime" --part-name file --auth \
  --title '4.6.3 파트 이름 오타 (차단 기대)'
printf '\n'
invoke_api_upload --path "$path" --file-path "$temp_dir/huge.png" --mime "$mime" --auth \
  --title '4.6.3 10MB 초과 업로드 (차단 기대)'
printf '\n'

if [[ -n "$draft" && "$skip_register" != true ]]; then
  payload="$(python3 - "$draft" "$run_tag" <<'PY'
import json,sys
draft=json.loads(sys.argv[1]); draft['title']=f"[{sys.argv[2]}] {draft.get('title') or ''}"
keys=('category','field','title','organizer','targetSchool','startDate','endDate','detailUrl',
      'imageUrl','description','summarizedDescription','recommendedTargets','externalId')
print(json.dumps({k:draft[k] for k in keys if draft.get(k) not in (None,'')},ensure_ascii=False))
PY
)"
  invoke_api --method POST --path /api/events --auth --title '4.6.4 추출한 초안으로 활동 등록' --body "$payload"
  created=$mateon_response
  printf '\n'
  event_id="$(json_get "$created" data.id)"
  [[ -n "$event_id" ]] && result=true || result=false
  assert_test '4.6.4 초안이 그대로 활동으로 등록된다' "$result" "eventId=$event_id"
  if [[ -n "$event_id" ]]; then
    [[ "$(json_get "$created" data.imageUrl)" == "$(json_get "$draft" imageUrl)" ]] && result=true || result=false
    assert_test '4.6.4 등록된 활동에 초안의 imageUrl이 실린다' "$result"
    [[ "$(json_get "$created" data.field)" == "$(json_get "$draft" field)" ]] && result=true || result=false
    assert_test '4.6.4 등록된 활동의 분야가 초안과 같다' "$result"
    invoke_api --path "/api/events/search?keyword=$run_tag&size=10" --title '4.6.4 등록한 활동 검색'
    printf '\n'
    json_has_id "$mateon_response" "$event_id" && result=true || result=false
    assert_test '4.6.4 등록한 활동이 검색에 잡힌다' "$result"
  fi
fi
write_test_summary
