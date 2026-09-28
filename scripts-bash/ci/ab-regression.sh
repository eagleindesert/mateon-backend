#!/usr/bin/env bash
# 이미 overlay 된 트리에서 예전 테스트를 새 코드에 대해 돌리고 실패를 분류한다.
# CI 의 ab-regression job 이 src/test 를 base 것으로 바꾼 뒤에 호출한다.
#
# 종료 코드:
#   0  성공 (AB_RESULT=PASS)
#   1  예전 단언이 새 코드에서 깨짐 (AB_RESULT=REGRESSION)
#   2  예전 테스트가 새 시그니처와 안 맞음 (AB_RESULT=INCOMPATIBLE)
#   3  새 코드가 컴파일되지 않음 (AB_RESULT=COMPILE_JAVA)
# stdout 에 AB_RESULT=... 한 줄을 남긴다. 워크플로가 읽어 Step Summary 에 적는다.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"

if [[ ! -x ./gradlew ]]; then
  chmod +x ./gradlew
fi

run_gradle() {
  ./gradlew "$@" --no-daemon
}

set +e
run_gradle compileJava
rc=$?
set -e
if [[ "$rc" -ne 0 ]]; then
  echo "AB_RESULT=COMPILE_JAVA"
  exit 3
fi

set +e
run_gradle compileTestJava
rc=$?
set -e
if [[ "$rc" -ne 0 ]]; then
  echo "AB_RESULT=INCOMPATIBLE"
  exit 2
fi

set +e
run_gradle test -x jacocoTestReport -x jacocoTestCoverageVerification
rc=$?
set -e
if [[ "$rc" -ne 0 ]]; then
  echo "AB_RESULT=REGRESSION"
  exit 1
fi

echo "AB_RESULT=PASS"
exit 0
