#!/usr/bin/env bash
# 로컬 테스트용 카카오 인가코드 → 액세스 토큰 교환
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
env_file="$script_dir/../.env"

if [[ -f "$env_file" ]]; then
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
    [[ "$line" == *=* ]] || continue
    key="${line%%=*}"
    key="${key//[[:space:]]/}"
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    value="${line#*=}"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    if [[ "$value" == \"*\" || "$value" == \'*\' ]]; then value="${value:1:${#value}-2}"; fi
    export "$key=$value"
  done < "$env_file"
fi

rest_api_key="${MATEON_KAKAO_REST_API_KEY:-}"
redirect_uri="${MATEON_KAKAO_REDIRECT_URI:-http://localhost:8080/debug/oauth}"
client_secret="${MATEON_KAKAO_CLIENT_SECRET:-}"

printf '\n########## 카카오 액세스 토큰 자동 획득 ##########\n'
if [[ -z "$rest_api_key" ]]; then
  echo 'MATEON_KAKAO_REST_API_KEY가 없습니다. scripts/debug/.env에 설정하세요.' >&2
  exit 1
fi

authorize_url="https://kauth.kakao.com/oauth/authorize?response_type=code&client_id=$rest_api_key&redirect_uri=$redirect_uri"
printf '\n[1] 아래 URL을 브라우저로 열어 카카오 로그인/동의를 진행하세요:\n    %s\n' "$authorize_url"
echo "    완료 페이지의 code 값 또는 주소창의 ?code= 값을 복사하세요."
printf '\n[2] 브라우저에서 확인한 인가코드를 붙여넣으세요.\n  인가코드: '
IFS= read -r code
code="${code#"${code%%[![:space:]]*}"}"
code="${code%"${code##*[![:space:]]}"}"
[[ -n "$code" ]] || { echo '인가코드가 비어 있습니다.' >&2; exit 1; }
printf '  (i) 인가코드 확보: %.12s...\n' "$code"

printf '\n[3] kauth.kakao.com/oauth/token으로 교환 중...\n'
args=(-sS -X POST 'https://kauth.kakao.com/oauth/token'
  -H 'Content-Type: application/x-www-form-urlencoded'
  --data-urlencode 'grant_type=authorization_code'
  --data-urlencode "client_id=$rest_api_key"
  --data-urlencode "redirect_uri=$redirect_uri"
  --data-urlencode "code=$code")
[[ -n "$client_secret" ]] && args+=(--data-urlencode "client_secret=$client_secret")
raw="$(curl "${args[@]}")"
token="$(python3 -c 'import json,sys
try: print(json.loads(sys.argv[1]).get("access_token", ""))
except ValueError: print("")' "$raw")"
if [[ -z "$token" ]]; then
  printf '액세스 토큰 교환 실패. 카카오 응답:\n%s\n' "$raw" >&2
  exit 1
fi
expires_in="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1]).get("expires_in", ""))' "$raw")"
printf '  (i) access token 획득 (expires_in=%ss)\n' "$expires_in"

# 토큰이 들어간 .env는 기존 파일 권한을 유지하고, 새 파일이면 소유자만 읽게 한다.
umask 077
python3 - "$env_file" "$token" <<'PY'
import pathlib, re, sys
path = pathlib.Path(sys.argv[1])
token = sys.argv[2]
lines = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
key = "MATEON_KAKAO_ACCESS_TOKEN"
pattern = re.compile(r"^\s*" + key + r"\s*=")
updated = [key + "=" + token if pattern.match(line) else line for line in lines]
if not any(pattern.match(line) for line in lines):
    updated.append(key + "=" + token)
path.write_text("\n".join(updated) + "\n", encoding="utf-8")
PY
printf '\n[4] %s에 MATEON_KAKAO_ACCESS_TOKEN 기록 완료\n' "$env_file"
