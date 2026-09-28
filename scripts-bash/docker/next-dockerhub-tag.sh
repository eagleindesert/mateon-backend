#!/usr/bin/env bash
# DockerHub 다음 semver 태그를 출력한다.
# 조회·폴백(v1.0.0) 규칙은 deploy-dockerhub.ps1 의 Get-NextDockerHubTag 와 같다.
# BUMP(patch/minor/major) 와 TAG(직접 지정) 는 CI 수동 실행용 확장이다. 한쪽 규칙을
# 바꾸면 다른 쪽도 맞출 것.
#
# public 레포지토리라 인증 없이 조회한다. 실패/미존재 시 START_VERSION(기본 v1.0.0).
# GitHub Actions 에서는 GITHUB_OUTPUT 에 tag= 를 쓰고, 로컬에서는 stdout 에 태그만 찍는다.
set -euo pipefail

USERNAME="${DOCKERHUB_USERNAME:?DOCKERHUB_USERNAME is required}"
IMAGE="${DOCKERHUB_IMAGE:-mateon-backend}"
START_VERSION="${START_VERSION:-v1.0.0}"
BUMP="${BUMP:-patch}"
TAG="${TAG:-}"
REPO="${USERNAME}/${IMAGE}"
URL="https://hub.docker.com/v2/repositories/${REPO}/tags/?page_size=100"

PYTHON=""
for candidate in python3 python; do
  if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c "import json, re, urllib.request" >/dev/null 2>&1; then
    PYTHON="$candidate"
    break
  fi
done
if [[ -z "$PYTHON" ]]; then
  echo "python3 (or python) with urllib is required" >&2
  exit 1
fi

tag="$("$PYTHON" - "$URL" "$START_VERSION" "$BUMP" "$TAG" <<'PY'
import json, re, sys, urllib.request

url, start, bump, explicit = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4].strip()
pat = re.compile(r"^(v?)(\d+)\.(\d+)\.(\d+)$")

if explicit:
    if not pat.match(explicit):
        print(f"TAG 가 semver 가 아닙니다: {explicit} (예: v1.1.0, v2.0.0)", file=sys.stderr)
        sys.exit(1)
    print(explicit)
    sys.exit(0)

if bump not in ("patch", "minor", "major"):
    print(f"BUMP 는 patch, minor, major 중 하나여야 합니다: {bump}", file=sys.stderr)
    sys.exit(1)

try:
    with urllib.request.urlopen(url, timeout=30) as resp:
        data = json.load(resp)
except Exception:
    print(start)
    sys.exit(0)

semver = []
for t in data.get("results") or []:
    m = pat.match(t.get("name") or "")
    if m:
        semver.append((m.group(1), int(m.group(2)), int(m.group(3)), int(m.group(4))))

if not semver:
    print(start)
    sys.exit(0)

semver.sort(key=lambda x: (x[1], x[2], x[3]))
prefix, major, minor, patch = semver[-1]
if bump == "major":
    print(f"{prefix}{major + 1}.0.0")
elif bump == "minor":
    print(f"{prefix}{major}.{minor + 1}.0")
else:
    print(f"{prefix}{major}.{minor}.{patch + 1}")
PY
)"

echo "next DockerHub tag: ${tag}" >&2
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  echo "tag=${tag}" >> "$GITHUB_OUTPUT"
else
  printf '%s\n' "$tag"
fi
