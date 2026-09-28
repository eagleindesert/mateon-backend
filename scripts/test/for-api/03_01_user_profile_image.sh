#!/usr/bin/env bash
set -u
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/00_common.sh"
image_path=''; timeout_sec=30
while (($#)); do
  case "$1" in
    --image-path) image_path=${2:?이미지 경로가 필요합니다}; shift 2 ;;
    --timeout-sec) timeout_sec=${2:?초 값이 필요합니다}; shift 2 ;;
    -h|--help) echo '사용법: 03_01_user_profile_image.sh [--image-path FILE] [--timeout-sec 30]'; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
printf '\n########## 3-1. 프로필 사진 ##########\n'
temp_dir="$(mktemp -d)"
trap 'rm -rf "$temp_dir"' EXIT
if [[ -n "$image_path" ]]; then
  [[ -f "$image_path" ]] || { echo "이미지가 없습니다: $image_path" >&2; exit 1; }
  png_path="$image_path"; replace_path="$image_path"
else
  png_path="$temp_dir/profile-1.png"; replace_path="$temp_dir/profile-2.png"
  pixel='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='
  printf '%s' "$pixel" | base64 -d > "$png_path"
  printf '%s' "$pixel" | base64 -d > "$replace_path"
fi
mime=image/png
[[ "$png_path" =~ \.[Jj][Pp][Ee]?[Gg]$ ]] && mime=image/jpeg
gif_path="$temp_dir/invalid.gif"; cp -- "$png_path" "$gif_path"
huge_path="$temp_dir/huge.png"; truncate -s 11M "$huge_path"
[[ -n "$(get_access_token || true)" ]] || { echo 'accessToken이 없습니다.' >&2; exit 1; }
my_id="$(jwt_subject "$(get_access_token)")"
path='/api/users/me/profile-image'

get_image_url() {
  local raw token
  token="$(get_access_token)"
  raw="$(curl -sS -H "Authorization: Bearer $token" "$mateon_base_url/api/users/me" || true)"
  json_get "$raw" data.profileImageUrl
}
wait_image_url() {
  local expected=$1 mode=$2 deadline now current
  deadline=$(( $(date +%s) + timeout_sec ))
  while :; do
    current="$(get_image_url)"
    if [[ "$mode" == changed && -n "$current" && "$current" != "$expected" ]] ||
       [[ "$mode" == empty && -z "$current" ]]; then printf '%s' "$current"; return 0; fi
    now=$(date +%s)
    ((now >= deadline)) && { printf '%s' "$current"; return 1; }
    sleep 0.7
  done
}
url_probe() { curl -s -o /dev/null -w '%{http_code} %{content_type}' "$1" || true; }

invoke_api_upload --path "$path" --file-path "$png_path" --mime "$mime" \
  --title '3.6.1 사진 업로드 (비인증 - 차단 기대)'
printf '\n'
invoke_api --method DELETE --path "$path" --title '3.6.1 사진 삭제 (비인증 - 차단 기대)'
printf '\n'
invoke_api_upload --path "$path" --file-path "$gif_path" --mime image/gif --auth \
  --title '3.6.2 gif 업로드 (차단 기대)'
printf '\n'
invoke_api_upload --path "$path" --file-path "$png_path" --mime "$mime" --part-name file --auth \
  --title '3.6.2 파트 이름 오타 (차단 기대)'
printf '\n'
invoke_api_upload --path "$path" --file-path "$huge_path" --mime "$mime" --auth \
  --title '3.6.2 10MB 초과 업로드 (차단 기대)'
printf '\n'

before="$(get_image_url)"
invoke_api_upload --path "$path" --file-path "$png_path" --mime "$mime" --auth \
  --title '3.6.3 사진 업로드'
accepted=$mateon_response
printf '\n'
[[ -z "$(json_get "$accepted" data)" ]] && result=true || result=false
assert_test '3.6.3a 접수 응답에는 이미지 URL이 없다' "$result"
uploaded="$(wait_image_url "$before" changed || true)"
[[ -n "$uploaded" && "$uploaded" != "$before" ]] && result=true || result=false
assert_test '3.6.3b GET /me에 새 URL이 나타난다' "$result" "$uploaded"
if [[ -z "$uploaded" ]]; then write_test_summary; exit $?; fi
[[ "$uploaded" =~ ^https?:// ]] && result=true || result=false
assert_test '3.6.3c profileImageUrl이 절대 URL이다' "$result"
probe="$(url_probe "$uploaded")"
[[ "$probe" =~ ^200[[:space:]]+image/ ]] && result=true || result=false
assert_test '3.6.3d 업로드된 사진이 인증 없이 열린다' "$result" "$probe"

invoke_api --path /api/users/mypage --auth --title '3.6.4 마이페이지 조회'
mypage=$mateon_response
printf '\n'
[[ "$(json_get "$mypage" data.profileImageUrl)" == "$uploaded" ]] && result=true || result=false
assert_test '3.6.4a 마이페이지에 같은 profileImageUrl이 실린다' "$result"
invoke_api --path "/api/users/$my_id" --auth --title '3.6.4 공개 프로필 조회'
public_profile=$mateon_response
printf '\n'
[[ "$(json_get "$public_profile" data.profileImageUrl)" == "$uploaded" ]] && result=true || result=false
assert_test '3.6.4b 공개 프로필에 같은 profileImageUrl이 실린다' "$result"
if json_has_key "$public_profile" data.email || json_has_key "$public_profile" data.schoolEmail; then result=false; else result=true; fi
assert_test '3.6.4c 공개 프로필에 이메일이 없다' "$result"

invoke_api_upload --path "$path" --file-path "$replace_path" --mime "$mime" --auth \
  --title '3.6.5 사진 재업로드'
printf '\n'
replaced="$(wait_image_url "$uploaded" changed || true)"
[[ -n "$replaced" && "$replaced" != "$uploaded" ]] && result=true || result=false
assert_test '3.6.5a 재업로드 후 다른 URL로 바뀐다' "$result"
if [[ -n "$replaced" && "$replaced" != "$uploaded" ]]; then
  probe="$(url_probe "$replaced")"
  [[ "$probe" =~ ^200[[:space:]]+image/ ]] && result=true || result=false
  assert_test '3.6.5b 새 사진이 열린다' "$result" "$probe"
  probe="$(url_probe "$uploaded")"
  [[ "$probe" =~ ^200[[:space:]] ]] && result=false || result=true
  assert_test '3.6.5c 이전 객체는 버킷에서 지워졌다' "$result" "$probe"
fi

invoke_api --method DELETE --path "$path" --auth --title '3.6.6 사진 삭제'
printf '\n'
cleared="$(wait_image_url '' empty || true)"
[[ -z "$cleared" ]] && result=true || result=false
assert_test '3.6.6a 삭제 후 profileImageUrl이 null로 돌아간다' "$result"
if [[ -n "$replaced" ]]; then
  probe="$(url_probe "$replaced")"
  [[ "$probe" =~ ^200[[:space:]] ]] && result=false || result=true
  assert_test '3.6.6b 삭제된 객체는 열리지 않는다' "$result" "$probe"
fi
invoke_api --method DELETE --path "$path" --auth --title '3.6.6 사진 없는 상태에서 다시 삭제'
printf '\n'
write_test_summary
