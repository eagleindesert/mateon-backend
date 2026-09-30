#!/usr/bin/env bash
# 전체 API 테스트를 원본과 같은 순서로 실행한다.
set -u
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/../../lib/colors.sh"
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
    *) mateon_color_printf Red '%s\n' "알 수 없는 인자: $1" >&2; exit 2 ;;
  esac
done
mateon_color_printf Yellow '\n[!] 전체 실행은 실제 LLM/임베딩을 약 50회 호출할 수 있습니다.\n'
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

# Bash children cannot share the PowerShell global tracker. Collect their structured
# results in a private temporary file, then merge each section into this process.
source "$script_dir/00_common.sh"
results_file="$(mktemp)" || exit 1
cleanup_results() { rm -f -- "$results_file"; }
trap cleanup_results EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
run_test() {
  local script=$1 label=$2; shift 2
  local code=0 failed_before=$mateon_failed kind title status method path detail section
  mateon_color_printf Magenta '\n===== %s =====\n' "$label"
  if [[ ! -f "$script_dir/$script" ]]; then
    mateon_color_printf Red '스크립트가 없습니다: %s\n' "$script" >&2
    mateon_record_result FAIL "$label" MISSING SCRIPT "$script" '스크립트가 없습니다.' "$script"
    return 0
  fi
  : > "$results_file"
  MATEON_RESULTS_FILE="$results_file" MATEON_TEST_SECTION="$script" \
    bash "$script_dir/$script" "$@" || code=$?
  while IFS= read -r -d '' kind && IFS= read -r -d '' title &&
        IFS= read -r -d '' status && IFS= read -r -d '' method &&
        IFS= read -r -d '' path && IFS= read -r -d '' detail &&
        IFS= read -r -d '' section; do
    mateon_record_result "$kind" "$title" "$status" "$method" "$path" "$detail" "$section"
  done < "$results_file"
  # Early exits and runtime errors may happen before a test result is recorded.
  # A summary's nonzero exit is already represented by its failed tests.
  if ((code != 0 && mateon_failed == failed_before)); then
    mateon_record_result FAIL "$label" "EXIT_$code" SCRIPT "$script" "스크립트 종료 코드: $code" "$script"
  fi
  return 0
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
mateon_color_printf Green '\n===== 전체 테스트 완료 =====\n'
write_test_summary
exit "$?"
