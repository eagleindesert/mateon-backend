# 12_team_embedding.ps1 (for-api-server) - 팀 임베딩 연동 테스트  /api/teams + AI embedding:refresh
#
# ============================================================================
#  [!] 과금 주의 - 이 스크립트는 실제 LLM / 임베딩을 호출합니다.
#      팀 생성/수정이 커밋되면 비동기로 AI 서버가 intro_text 추출(LLM) + 임베딩을 수행합니다.
#      API 응답은 즉시 오지만 과금은 그 뒤에 발생합니다 — 응답이 빨랐다고 안 부른 게 아닙니다.
#      예상 호출: 3회 (팀 생성 2 + 수정 1)
#
#      과금 없이 돌리려면 백엔드가 로컬 스텁을 보게 하세요:
#        pwsh -File ..\debug\ai-stub\stub-ai-server.ps1     # 포트 8000
#        백엔드 AI_BASE_URL=http://localhost:8000 으로 재기동
# ============================================================================
#
# 사용법: pwsh -File .\12_team_embedding.ps1            # 기본: 만든 팀을 남긴다 (05_team 과 같은 관례)
#        pwsh -File .\12_team_embedding.ps1 -Cleanup    # 팀 삭제까지 수행 - FK CASCADE 검증 포함
# 사전 조건:
#   1) 로그인이 선행되어 .auth-token.txt 가 있어야 한다 (99_run_all 또는 auth\02_auth.ps1).
#   2) 백엔드의 ai.base-url 이 살아있는 AI 서버(또는 스텁)를 가리켜야 한다.
#      단, 임베딩 갱신은 "커밋 후 비동기"라 AI 서버가 죽어있어도 팀 CRUD 는 전부 성공해야
#      한다 — 그 자체가 이 기능의 핵심 검증이다 (실패는 백엔드 warn 로그로만 남는다).
#
# [검증 범위]
#   - requiredSkills 를 포함/미포함 양쪽으로 팀을 생성해도 즉시 성공하는지 (optional 필드)
#   - 응답에 requiredSkills 가 노출되는지
#   - 수정/삭제가 임베딩 행 존재와 무관하게 성공하는지 (삭제 = FK CASCADE 검증)
#   - AI 호출 자체는 비동기라 API 응답으로 확인 불가. 스텁 콘솔 덤프 또는 DB 로 확인:
#       SELECT embedding_text, activity_goal, missing_fields, vector_dims(embedding)
#       FROM team_embeddings WHERE team_id=<id>;
#
# [주의] 기본 실행은 팀을 삭제하지 않는다 — 실행 후 team_embeddings 를 조회해 임베딩/메타데이터를
#        직접 확인할 수 있다. -Cleanup 을 주면 팀 삭제까지 수행하며, 이때 임베딩 행이 FK CASCADE 로
#        함께 지워지는 것(12.4a)까지 검증한다. 검증 후 DB 에 행이 안 남는 게 정상이다.
param(
    [switch]$Cleanup
)
. "$PSScriptRoot\00_common.ps1"

Write-Host "`n########## 12. Team Embedding (팀 임베딩 연동) - /api/teams [인증 필요] ##########" -ForegroundColor Magenta

if (-not (Get-AccessToken)) {
    Write-Host "(!) accessToken 이 없습니다. 먼저 .\auth\02_auth.ps1 (또는 99_run_all) 을 실행하세요." -ForegroundColor Red
    return
}

# ── 12.1 requiredSkills 포함 생성 ──────────────────────────────────────────
$created = Invoke-Api -Method POST -Path "/api/teams" -Auth -PassThru -Title "12.1 팀 생성 (requiredSkills 포함)" -Body @{
    eventId              = $null
    title                = "임베딩테스트 팀 $((Get-Random -Maximum 9999))"
    promotionText        = "커머스 플랫폼을 만드는 팀입니다. 현재 FE 2명, Design 1명으로 구성돼 있습니다. 매주 화, 목요일 저녁 오프라인으로 모이고, 초보자도 편하게 참여할 수 있는 분위기를 지향합니다. 이번 학기 교내 공모전 수상이 목표입니다."
    role                 = @("BE")
    requiredSkills       = @("Spring Boot", "PostgreSQL")
    characteristic       = "초보 환영"
    capacity             = 4
    recruitmentStartDate = (Get-Date).ToString("yyyy-MM-dd")
    recruitmentEndDate   = (Get-Date).AddDays(30).ToString("yyyy-MM-dd")
}

if (-not $created.success) {
    Assert-Test -Title "12.1 팀 생성 성공" -Condition $false -Detail $created.message
    return
}
$teamId = $created.data.id
Write-Host "  (i) 생성된 teamId = $teamId" -ForegroundColor Green

# 생성이 즉시 성공했다는 것 자체가 "AI 호출이 응답을 붙잡지 않는다(비동기)"의 간접 검증이다.
# (AI read-timeout 은 60초 — 동기였다면 스텁이 없을 때 여기서 한참 걸리거나 5xx 가 난다)
Assert-Test -Title "12.1a 응답에 requiredSkills 노출" `
    -Condition ((@($created.data.requiredSkills) -join ",") -eq "Spring Boot,PostgreSQL") `
    -Detail ("requiredSkills=[{0}]" -f (@($created.data.requiredSkills) -join ", "))

# ── 12.2 requiredSkills 없이 생성 (optional 검증 — 기존 프론트 호환) ────────
$created2 = Invoke-Api -Method POST -Path "/api/teams" -Auth -PassThru -Title "12.2 팀 생성 (requiredSkills 미전송 → 성공 기대)" -Body @{
    eventId              = $null
    title                = "임베딩테스트 팀(스킬없음) $((Get-Random -Maximum 9999))"
    promotionText        = "requiredSkills 없이도 생성되어야 합니다."
    role                 = @("프론트엔드")
    characteristic       = "테스트"
    capacity             = 3
    recruitmentStartDate = (Get-Date).ToString("yyyy-MM-dd")
    recruitmentEndDate   = (Get-Date).AddDays(15).ToString("yyyy-MM-dd")
}
Assert-Test -Title "12.2a requiredSkills 없이 생성 성공 (optional)" -Condition ([bool]$created2.success) -Detail $created2.message
$teamId2 = $created2.data.id

# ── 12.3 수정 → 임베딩 재계산 트리거 ───────────────────────────────────────
if ($teamId) {
    $updated = Invoke-Api -Method PUT -Path "/api/teams/$teamId" -Auth -PassThru -Title "12.3 팀 수정 (requiredSkills 변경 → 재계산 트리거)" -Body @{
        eventId              = $null
        title                = "임베딩테스트 팀 (수정됨)"
        promotionText        = "이제 온라인 위주로 모입니다. 대회 입상이 목표입니다."
        role                 = @("BE", "Design")
        requiredSkills       = @("Java", "Redis")
        characteristic       = "빡센 팀"
        capacity             = 5
        recruitmentStartDate = (Get-Date).ToString("yyyy-MM-dd")
        recruitmentEndDate   = (Get-Date).AddDays(20).ToString("yyyy-MM-dd")
    }
    Assert-Test -Title "12.3a 수정 성공 + requiredSkills 갱신" `
        -Condition ($updated.success -and ((@($updated.data.requiredSkills) -join ",") -eq "Java,Redis")) `
        -Detail ("requiredSkills=[{0}]" -f (@($updated.data.requiredSkills) -join ", "))
}

# 비동기 임베딩 저장이 돌 시간을 살짝 준다 (스텁이면 순식간, 실서버 LLM 이면 수 초).
Write-Host "  (i) 비동기 임베딩 저장 대기 (3초)..." -ForegroundColor DarkGray
Start-Sleep -Seconds 3

# ── 12.4 삭제 → FK CASCADE 검증 (-Cleanup 일 때만) ─────────────────────────
# 임베딩 행이 생긴 뒤에도 삭제가 성공해야 한다 (V8 의 ON DELETE CASCADE).
# CASCADE 가 없으면 여기서 FK 위반 500 이 난다.
# 기본 실행은 팀을 남긴다 — team_embeddings 행을 DB 에서 직접 확인할 수 있게 (05_team 관례).
if ($Cleanup) {
    if ($teamId) {
        $deleted = Invoke-Api -Method DELETE -Path "/api/teams/$teamId" -Auth -PassThru -Title "12.4 팀 삭제 (임베딩 행 CASCADE 기대)"
        Assert-Test -Title "12.4a 임베딩 행 존재 상태에서 삭제 성공 (FK CASCADE)" -Condition ([bool]$deleted.success) -Detail $deleted.message
    }
    if ($teamId2) {
        Invoke-Api -Method DELETE -Path "/api/teams/$teamId2" -Auth -Title "12.4b 뒷정리 (스킬없음 팀 삭제)"
    }
} else {
    Write-Host ""
    Write-Host "  (i) 만든 팀을 남깁니다 (teamId=$teamId, teamId2=$teamId2)." -ForegroundColor Yellow
    Write-Host "      team_embeddings 에서 임베딩/메타데이터를 직접 확인하세요." -ForegroundColor Yellow
    Write-Host "      삭제 + FK CASCADE 검증까지 하려면 -Cleanup 을 붙여 실행하세요." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "  (i) 임베딩/메타데이터 저장 결과는 API 로 노출되지 않습니다. 확인 방법:" -ForegroundColor DarkGray
Write-Host "      - 스텁 사용 시: 스텁 콘솔에 intro_text/recruiting_roles/required_skills 덤프 확인" -ForegroundColor DarkGray
Write-Host "      - DB: SELECT embedding_text, activity_goal, missing_fields, vector_dims(embedding)" -ForegroundColor DarkGray
Write-Host "            FROM team_embeddings WHERE team_id=$teamId;" -ForegroundColor DarkGray

Write-Host "`n########## 12. Team Embedding 테스트 완료 ##########" -ForegroundColor Magenta
