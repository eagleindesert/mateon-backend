# 스크립트

`./start.sh [VERSION]`은 모든 모드에서 DockerHub 이미지를 pull한 뒤 빌드 없이 실행합니다.
AI 스텁도 게시된 이미지를 사용하며, pull에 실패하면 실행을 중단합니다.
`./scripts/build-start.sh [VERSION]`은 앱을 빌드한 뒤 같은 Docker 데몬에서 실행합니다.
테스트 모드에서는 AI 스텁도 같은 태그로 빌드하고, DockerHub에 push하지 않습니다.

두 스크립트 모두 `--mode server|loadtest|ai-stub`, `--tag`, `--db-pool-size`, `--port`를 지원합니다.
`build-start.sh`는 추가로 `--platform`, `--username`, `--image-name`을 지원하며 태그 기본값은 `local`입니다.
서버 모드는 배포 Compose와 루트 `.env`, 테스트 모드는 독립 Compose와 `config/loadtest.properties`를
사용합니다. 테스트는 [JMeter 실행 안내](test/jmeter/README.md)를 참고하세요.
자세한 절차는 [루트 README](../README.md#배포와-컨테이너-재실행)를 참고하세요.

배포 스크립트 검증은 `uv run --no-project scripts/test/test_deployment.py` 로 실행합니다.
임시 디렉터리에서 Compose 설정과 Docker 호출 순서·실패 처리를 검사하며 실제 컨테이너를 실행하지 않습니다.

`scripts` 아래의 PowerShell 스크립트 37개를 같은 상대 경로와 파일 이름의 `.sh`로 옮겼습니다. 실행할 때 PowerShell이 필요하지 않습니다. API 테스트에는 Bash, curl, Python 3가 필요합니다. Docker 배포에는 Docker CLI와 buildx가 필요합니다.

### `.env` / `.env.secret` 양식 갱신

기본 실행은 프로젝트 루트의 두 파일을 각각의 예제에 맞춰 갱신합니다. Python 3.10 이상과 `uv`를 사용하며 추가 패키지는 필요 없습니다.

| 갱신할 파일 | 양식으로 사용할 파일 |
| --- | --- |
| `.env` | `.env.example` |
| `.env.secret` | `.env.secret.example` |

```bash
uv run scripts/sync_env.py --dry-run
uv run scripts/sync_env.py
```

두 파일 모두 템플릿의 항목 순서, 설명 주석, 빈 줄을 적용합니다. 기존 값은 빈 값, 따옴표, 인라인 주석, 따옴표로 감싼 여러 줄 값까지 그대로 유지합니다. 기존 항목의 활성/주석 상태와 `export`도 유지하므로, 템플릿에서 주석 처리된 항목이라도 기존 파일에서 사용 중이면 활성 상태로 배치합니다. 기존 설명 주석은 템플릿의 설명으로 교체합니다.

새 항목은 템플릿의 값과 주석 상태를 사용하고, 템플릿에 없는 기존 키는 각 파일 끝에 보존합니다. 대상 파일이 없으면 해당 템플릿으로 생성합니다. 두 파일을 모두 검증한 뒤 갱신하므로, 형식 오류, 활성 키 중복, 값을 보존할 수 없는 모호한 주석 예시가 있으면 두 파일 모두 변경하지 않고 중단합니다.

변경할 때마다 기존 파일을 같은 디렉토리의 `.env.bak.*` / `.env.secret.bak.*`에 각각 백업하고 기존 파일 권한을 유지합니다. 변경 사항이 없으면 파일과 백업을 만들지 않습니다. `--dry-run`은 변경 필요 여부만 표시하며, 실행 결과에 설정값은 출력하지 않습니다.

`--example` 또는 `--env`를 지정하면 해당 파일 쌍만 처리합니다. 생략한 인수는 프로젝트 루트의 `.env.example` / `.env`를 사용합니다. `.env.secret`만 갱신하거나 다른 파일 쌍에 적용할 때는 두 경로를 함께 지정하세요.

```bash
uv run scripts/sync_env.py --example .env.secret.example --env .env.secret
uv run scripts/sync_env.py --example scripts/test/.env.example --env scripts/test/.env
```

### API 테스트

전체 API 테스트:

```bash
bash scripts/test/for-api/99_run_all.sh \
  --email EMAIL --password PASSWORD \
  --user-b-email EMAIL_B --user-b-password PASSWORD_B \
  --user-c-email EMAIL_C --user-c-password PASSWORD_C
```

개별 테스트는 `bash scripts/test/for-api/15_review.sh`처럼 실행합니다. `00_common.sh`는 API 테스트에서 사용하는 공통 함수 파일입니다. `16_recommendation_reason.sh`와 `17_proposal_assembly.sh`는 `--cleanup` 옵션으로 만든 팀을 삭제할 수 있습니다. `19_ai_gateway.sh`는 `--strict-routing` 옵션을 지원합니다.

스크립트 전용 환경 설정은 아래 네 디렉토리에서 각각 하나의 `.env`로 관리합니다. 사용할 도구가 해당 파일을 읽는 경우에만 `.env.example`을 같은 디렉토리의 `.env`로 복사하고 필요한 값을 채우세요. 모든 실행 파일이 이 네 파일을 읽는 것은 아닙니다.

| 템플릿 | 설정 및 로드 방식 |
| --- | --- |
| `ci/.env.example` | 회귀 검사에는 필수 설정 없음. 선택한 JDK/Gradle 설정은 실행 전에 셸에 export |
| `debug/.env.example` | CORS 주소, Node.js 채팅 A/B 계정, 카카오 키/토큰. 해당 도구가 `debug/.env` 자동 로드 |
| `docker/.env.example` | DockerHub 사용자명/토큰 및 태그 계산 설정. `deploy-dockerhub.sh`가 `docker/.env` 자동 로드 |
| `test/.env.example` | API 테스트 A/B/C 계정, 학교 이메일, 카카오 토큰, 선택적 LiveTest 설정. API 스크립트가 `test/.env` 자동 로드 |

### 디렉토리별 `.env`를 설정할 필요가 없는 실행 파일

파일이 `scripts/debug`나 `scripts/ci`에 있다고 해서 해당 디렉토리의 `.env`를 읽는 것은 아닙니다. 백엔드 전체를 실행하는 도구는 프로젝트 루트 설정을 사용하고, CI에서 실행되는 도구는 워크플로가 제공하는 환경변수를 사용합니다.

| 실행 파일 | 실제 설정 출처 및 주의사항 |
| --- | --- |
| `debug/ai-stub/start-all.sh` | `scripts/debug/.env`를 읽지 않습니다. 프로젝트 루트 `.env.secret`의 존재와 `AI_INTERNAL_SECRET` 설정 여부를 직접 검사하고, `bootRun`으로 백엔드 전체를 실행합니다. 백엔드는 프로젝트 루트 `.env`와 `.env.secret`을 로드하므로 백엔드 설정은 루트에서 준비하세요. 스텁 옵션은 CLI로 전달합니다. |
| `ci/ab-regression.sh` | `scripts/ci/.env`를 읽지 않습니다. Gradle 컴파일과 테스트를 실행하며 테스트 프로필 설정은 `src/test/resources/application-test.yml`에 있습니다. 기본 실행에는 별도 `.env`가 필요 없습니다. |
| `docker/next-dockerhub-tag.sh` | `scripts/docker/.env`를 직접 읽지 않습니다. CI에서는 `.github/workflows/ci.yml`이 DockerHub 사용자명과 태그 옵션을 환경변수로 제공합니다. 배포 스크립트에서 호출하면 그 스크립트가 로드한 값을 전달받습니다. 로컬 단독 실행 시에는 아래 예시처럼 환경변수를 준비하세요. |
| `debug/ai-stub/stub-ai-server.sh`, `debug/ai-stub/_stub_ai_server.py`, `debug/ai-stub/stub-spring-ai-server.sh` | `.env`를 읽지 않습니다. 포트, 시크릿 검증, 실패 모드는 CLI 옵션으로 지정하며 기본 옵션으로 실행할 때는 별도 설정 파일이 필요 없습니다. |
| `debug/websocket-javascript/chat-client.js` | `.env`를 직접 읽지 않습니다. `launch.js`가 `scripts/debug/.env`에서 읽은 설정을 임시 JSON으로 전달합니다. 직접 실행할 때는 CLI 인자를 사용합니다. |
| `test/for-api/parallel-chat/_stomp_client.py`, `test/for-api/parallel-chat/_stomp_probe.py` | `.env`를 직접 읽지 않습니다. 호출하는 Bash 스크립트가 `scripts/test/.env`에서 읽은 값을 인자로 전달하므로 Python 파일용 `.env`를 따로 만들 필요가 없습니다. |

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

### 출력 색상

Bash/Python 도구는 `.legacies/scripts`의 대응 파일에 지정된 PowerShell 색상을 ANSI 색상으로 사용합니다. 제목은 Magenta, API 단계는 Cyan, 성공은 Green, 주의는 Yellow, 실패는 Red이며 안내 문구는 원본의 DarkGray/DarkCyan 등을 따릅니다. 공통 함수는 `lib/colors.sh`와 `lib/colors.py`에 있습니다.

터미널에서 실행하면 색상이 표시됩니다. 리다이렉트·파이프 또는 `TERM=dumb`에서는 일반 텍스트를 출력합니다. `NO_COLOR=1`로 색상을 끄거나 `FORCE_COLOR=1`로 강제할 수 있으며, 두 변수가 모두 있으면 `NO_COLOR`가 우선합니다. JSON 응답·토큰·SQL·STOMP 프레임 등 데이터에는 색상 코드를 넣지 않습니다.

`parallel-chat/chat-client.sh --color Cyan`과 `notification-client.sh --color Magenta`로 창별 색상을 지정할 수 있습니다. 채팅의 상대 메시지는 Yellow, 내 메시지 확인과 대화 이력은 DarkGray로 표시됩니다.
