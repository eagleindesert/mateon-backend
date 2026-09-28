#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/../00_common.sh"

clip=false
while (($#)); do
  case "$1" in
    --clip) clip=true; shift ;;
    -h|--help) echo '사용법: 00_before_auth.sh [--clip]'; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done

declare -a emails=() quoted=()
for email in "${MATEON_TEST_EMAIL:-}" "${MATEON_USERB_EMAIL:-}" "${MATEON_SCHOOL_EMAIL:-}"; do
  email="${email#"${email%%[![:space:]]*}"}"
  email="${email%"${email##*[![:space:]]}"}"
  [[ -n "$email" ]] || continue
  found=false
  for previous in "${emails[@]}"; do [[ "$previous" == "$email" ]] && found=true; done
  [[ "$found" == true ]] || emails+=("$email")
done

if ((${#emails[@]} == 0)); then
  echo '정리할 이메일이 없습니다. MATEON_TEST_EMAIL / MATEON_USERB_EMAIL / MATEON_SCHOOL_EMAIL을 확인하세요.' >&2
  exit 1
fi

for email in "${emails[@]}"; do
  escaped="${email//\'/\'\'}"
  quoted+=("'$escaped'")
done
in_list="$(IFS=', '; echo "${quoted[*]}")"
user_ids="SELECT id FROM users WHERE email IN ($in_list) OR school_email IN ($in_list)"
email_list="$(IFS=', '; echo "${emails[*]}")"

sql="$(cat <<EOF
-- ================================================================
--  회원가입 전 테스트 계정 정리 SQL (00_before_auth.sh 자동 생성)
--  대상 이메일: $email_list
-- ================================================================
BEGIN;

DELETE FROM chat_messages WHERE sender_id IN ($user_ids);
DELETE FROM chat_room_members WHERE user_id IN ($user_ids);
DELETE FROM team_applications
WHERE user_id IN ($user_ids)
   OR team_id IN (SELECT id FROM teams WHERE leader_user_id IN ($user_ids));
DELETE FROM teams WHERE leader_user_id IN ($user_ids);
DELETE FROM notification WHERE user_id IN ($user_ids);
DELETE FROM refresh_tokens WHERE user_id IN ($user_ids);
DELETE FROM email_verifications WHERE email IN ($in_list);
DELETE FROM users WHERE email IN ($in_list) OR school_email IN ($in_list);

COMMIT;
EOF
)"
printf '\n########## 0. Before-Auth DB 정리 SQL 생성 (BaseUrl=%s) ##########\n' "$mateon_base_url"
printf '  대상 이메일: %s\n  아래 SQL을 원격 DB에 붙여넣어 실행하세요.\n\n%s\n' "$email_list" "$sql"

if [[ "$clip" == true ]]; then
  if command -v wl-copy >/dev/null 2>&1; then
    printf '%s' "$sql" | wl-copy
  elif command -v xclip >/dev/null 2>&1; then
    printf '%s' "$sql" | xclip -selection clipboard
  elif command -v pbcopy >/dev/null 2>&1; then
    printf '%s' "$sql" | pbcopy
  else
    echo '클립보드 도구(wl-copy, xclip, pbcopy)를 찾을 수 없습니다.' >&2
    exit 1
  fi
  echo 'SQL을 클립보드에 복사했습니다.'
fi
