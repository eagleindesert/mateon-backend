#!/usr/bin/env bash
# PowerShell ConsoleColor names mapped to ANSI foreground colors.
# Only presentation output should use this helper; data stays plain.
mateon_color_printf() {
  local color=$1 code
  shift
  case "${color,,}" in
    black) code=30 ;; darkred) code=31 ;; darkgreen) code=32 ;;
    darkyellow) code=33 ;; darkblue) code=34 ;; darkmagenta) code=35 ;;
    darkcyan) code=36 ;; gray) code=37 ;; darkgray) code=90 ;;
    red) code=91 ;; green) code=92 ;; yellow) code=93 ;;
    blue) code=94 ;; magenta) code=95 ;; cyan) code=96 ;; white) code=97 ;;
    *) code=0 ;;
  esac
  if [[ -z "${NO_COLOR+x}" ]] && { [[ "${FORCE_COLOR:-0}" != 0 ]] || { [[ -t 1 ]] && [[ "${TERM:-}" != dumb ]]; }; }; then
    printf '\033[%sm' "$code"
    printf "$@"
    printf '\033[0m'
  else
    printf "$@"
  fi
}
