#!/usr/bin/env bash
set -u
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$script_dir/00_common.sh"
mateon_color_printf Magenta '\n########## 1. Health (헬스체크) ##########\n'
invoke_api --method GET --path / --title '1.1 루트 상태 확인'
printf '\n'
invoke_api --method GET --path /health --title '1.2 헬스 체크'
printf '\n'
write_test_summary
