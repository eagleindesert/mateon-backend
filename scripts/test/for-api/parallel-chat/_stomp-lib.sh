#!/usr/bin/env bash
# STOMP 프레임 생성과 WebSocket 채팅 클라이언트 실행.
stomp_frame() {
  local command=$1 headers=$2 body=${3:-}
  printf '%s\n%s\n\n%s\0' "$command" "$headers" "$body"
}
run_stomp_chat_client() {
  local lib_dir
  lib_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
  python3 "$lib_dir/_stomp_client.py" "$@"
}
