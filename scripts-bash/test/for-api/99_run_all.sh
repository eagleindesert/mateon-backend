#!/usr/bin/env bash
# 전체 API 테스트를 원본과 같은 순서로 실행한다.
set -u
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
email=""; password=""; user_b_email=""; user_b_password=""; user_c_email=""; user_c_password=""
while (($#)); do
  case "$1" in
    --email|--password|--user-b-email|--user-b-password|--user-c-email|--user-c-password)
      (($# >= 2)) || exit 2
      case "$1" in
        --email) email=$2 ;; --password) password=$2 ;;
        --user-b-email) user_b_email=$2 ;; --user-b-password) user_b_password=$2 ;;
        --user-c-email) user_c_email=$2 ;; --user-c-password) user_c_password=$2 ;;
      esac
      shift 2 ;;
    -h|--help) echo '사용법: 99_run_all.sh [--email EMAIL --password PASSWORD] [--user-b-email EMAIL ...]'; exit 0 ;;
    *) echo "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
printf '\n[!] 전체 실행은 실제 LLM/임베딩을 약 50회 호출할 수 있습니다.\n'
auth_args=(--login-only)
[[ -n "$email" ]] && auth_args+=(--email-a "$email")
[[ -n "$password" ]] && auth_args+=(--password-a "$password")
[[ -n "$user_b_email" ]] && auth_args+=(--email-b "$user_b_email")
[[ -n "$user_b_password" ]] && auth_args+=(--password-b "$user_b_password")
[[ -n "$user_c_email" ]] && auth_args+=(--email-c "$user_c_email")
[[ -n "$user_c_password" ]] && auth_args+=(--password-c "$user_c_password")
user_b_args=()
[[ -n "$user_b_email" ]] && user_b_args+=(--user-b-email "$user_b_email")
[[ -n "$user_b_password" ]] && user_b_args+=(--user-b-password "$user_b_password")

failed=0
run_test() {
  local script=$1 label=$2; shift 2
  printf '\n===== %s =====\n' "$label"
  if [[ ! -f "$script_dir/$script" ]]; then
    printf '스크립트가 없습니다: %s\n' "$script" >&2
    ((failed+=1))
    return 0
  fi
  bash "$script_dir/$script" "$@" || { code=$?; ((failed+=code)); }
}
run_test 01_health.sh '1) Health'
run_test auth/09_three_users.sh '2) 유저 A/B/C 로그인' "${auth_args[@]}"
run_test 03_00_user.sh '3) User'
run_test 03_01_user_profile_image.sh '3) User profile image'
run_test 03_02_portfolio_summarize.sh '3) Portfolio summarize'
run_test 04_00_event_init.sh '4) Event init'
run_test 04_01_event.sh '4) Event search'
run_test 04_02_event_extract_image.sh '4) Event poster extraction'
run_test 18_bookmark.sh '18) Bookmark'
run_test 20_contest_similarity_map.sh '20) Contest similarity map'
run_test 05_team.sh '5) Team'
run_test 06_notification.sh '6) Notification'
run_test 10_chat.sh '10) Chat' "${user_b_args[@]}"
run_test 11_matching_intent.sh '11) Matching intent'
run_test 12_team_embedding.sh '12) Team embedding'
run_test 13_recommendation.sh '13) Recommendation' "${user_b_args[@]}"
run_test 14_reverse_offer.sh '14) Reverse offer' "${user_b_args[@]}"
run_test 15_review.sh '15) Review'
run_test 16_recommendation_reason.sh '16) Recommendation reason' "${user_b_args[@]}"
run_test 17_proposal_assembly.sh '17) Proposal assembly' "${user_b_args[@]}"
run_test 19_ai_gateway.sh '19) AI gateway'
printf '\n===== 전체 테스트 완료 =====\n  실패: %d\n' "$failed"
((failed == 0))
