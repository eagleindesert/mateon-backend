# Bash 스크립트

`scripts` 아래의 PowerShell 스크립트 37개를 같은 상대 경로와 파일 이름의 `.sh`로 옮겼습니다. 실행할 때 PowerShell이 필요하지 않습니다. API 테스트에는 Bash, curl, Python 3가 필요합니다. Docker 배포에는 Docker CLI와 buildx가 필요합니다.

전체 API 테스트:

```bash
bash scripts-bash/test/for-api/99_run_all.sh \
  --email EMAIL --password PASSWORD \
  --user-b-email EMAIL_B --user-b-password PASSWORD_B \
  --user-c-email EMAIL_C --user-c-password PASSWORD_C
```

개별 테스트는 `bash scripts-bash/test/for-api/15_review.sh`처럼 실행합니다. `00_common.sh`는 API 테스트에서 사용하는 공통 함수 파일입니다. `16_recommendation_reason.sh`와 `17_proposal_assembly.sh`는 `--cleanup` 옵션으로 만든 팀을 삭제할 수 있습니다. `19_ai_gateway.sh`는 `--strict-routing` 옵션을 지원합니다.

API 설정은 `scripts-bash/test/for-api/.env` 또는 `MATEON_*` 환경변수로 지정합니다. 인증 테스트는 먼저 `auth/09_three_users.sh`를 실행해 A/B/C 토큰을 저장해야 합니다. 실제 백엔드와 AI 서비스가 필요한 통합 시나리오는 실행 환경에 따라 과금될 수 있습니다. 로컬 AI 스텁은 `scripts-bash/test/debug/ai-stub`에 있습니다.
