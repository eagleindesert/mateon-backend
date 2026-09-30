# Bash 스크립트

`scripts` 아래의 PowerShell 스크립트 37개를 같은 상대 경로와 파일 이름의 `.sh`로 옮겼습니다. 실행할 때 PowerShell이 필요하지 않습니다. API 테스트에는 Bash, curl, Python 3가 필요합니다. Docker 배포에는 Docker CLI와 buildx가 필요합니다.

전체 API 테스트:

```bash
bash scripts/test/for-api/99_run_all.sh \
  --email EMAIL --password PASSWORD \
  --user-b-email EMAIL_B --user-b-password PASSWORD_B \
  --user-c-email EMAIL_C --user-c-password PASSWORD_C
```

개별 테스트는 `bash scripts/test/for-api/15_review.sh`처럼 실행합니다. `00_common.sh`는 API 테스트에서 사용하는 공통 함수 파일입니다. `16_recommendation_reason.sh`와 `17_proposal_assembly.sh`는 `--cleanup` 옵션으로 만든 팀을 삭제할 수 있습니다. `19_ai_gateway.sh`는 `--strict-routing` 옵션을 지원합니다.

환경 설정은 아래 네 디렉토리에서 각각 하나의 `.env`로 관리합니다. 각 `.env.example`을 같은 디렉토리의 `.env`로 복사하고 필요한 값을 채우세요.

| 템플릿 | 설정 및 로드 방식 |
| --- | --- |
| `ci/.env.example` | 회귀 검사에는 필수 설정 없음. 선택한 JDK/Gradle 설정은 실행 전에 셸에 export |
| `debug/.env.example` | CORS 주소, Node.js 채팅 A/B 계정, 카카오 키/토큰. 해당 도구가 `debug/.env` 자동 로드 |
| `docker/.env.example` | DockerHub 사용자명/토큰 및 태그 계산 설정. `deploy-dockerhub.sh`가 `docker/.env` 자동 로드 |
| `test/.env.example` | API 테스트 A/B/C 계정, 학교 이메일, 카카오 토큰, 선택적 LiveTest 설정. API 스크립트가 `test/.env` 자동 로드 |

기존 `test/for-api/.env`, `debug/check-cors/.env`, `debug/oauth/.env`, `debug/websocket-javascript/.env`의 필요한 값은 각 상위 디렉토리의 `.env`로 옮겨야 합니다. 기존 파일은 자동으로 읽지 않습니다. API 테스트는 `MATEON_ENV_FILE` 환경변수로 다른 설정 파일을 지정할 수 있습니다. CLI 옵션, `.env`, 셸 환경변수, 기본값 순으로 우선합니다.

`next-dockerhub-tag.sh` 단독 실행과 `./gradlew liveTest`는 `.env`를 직접 읽지 않습니다. 해당 템플릿에 설명된 선택 설정을 사용하려면 실행 전에 셸로 내보내세요. 예:

```bash
set -a
source scripts/docker/.env
set +a
bash scripts/docker/next-dockerhub-tag.sh
```

CI의 GitHub 토큰/runner 변수는 GitHub Actions가 제공하고, DockerHub Secrets는 GitHub 저장소 설정에 따로 등록합니다. 백엔드와 Docker Compose의 설정은 프로젝트 루트 `.env` / `.env.secret` 및 해당 예제 파일에서 관리합니다. AI 스텁의 포트와 실패 모드는 CLI 옵션으로 지정합니다.

카카오 토큰 획득 도구는 `debug/.env`에 토큰을 기록합니다. API 테스트에 사용하려면 `test/.env`의 `MATEON_KAKAO_ACCESS_TOKEN`으로 복사하거나 `--kakao-access-token` 옵션으로 전달하세요.

인증 테스트는 먼저 `auth/09_three_users.sh`를 실행해 A/B/C 토큰을 저장해야 합니다. 실제 백엔드와 AI 서비스가 필요한 통합 시나리오는 실행 환경에 따라 과금될 수 있습니다. 로컬 AI 스텁은 `scripts/debug/ai-stub`에 있습니다.
