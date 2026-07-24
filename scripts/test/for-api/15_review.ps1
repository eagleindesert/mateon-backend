# 15_review.ps1 (for-api-server) - 협업 온도 / 팀원 평가  [인증 필요]
#
# ============================================================================
#  [!] 과금 주의 - 팀을 실제로 생성하므로 AI 가 간접 호출됩니다.
#      팀 생성이 커밋되면 비동기로 팀 임베딩 갱신(LLM 추출 + 임베딩)이 돕니다. 예상 1회.
#      과금 없이 돌리려면 백엔드 AI_BASE_URL 을 로컬 스텁(..\debug\ai-stub)으로 돌려두세요.
# ============================================================================
#
# 사용법:
#   pwsh -File .\auth\09_three_users.ps1   # 선행 필수: 유저 A/B/C 토큰 슬롯 확보
#   pwsh -File .\15_review.ps1
#
# 시나리오 (팀장 A + 팀원 B, C):
#   1. A 가 팀 생성 → B, C 가 지원 → A 가 둘 다 승인
#   2. 종료 전 평가 시도 → TEAM_NOT_ENDED 로 차단되는지 확인
#   3. B(팀장 아님)의 종료 시도 → 차단 확인
#   4. A 가 활동 종료 → 평가 개시
#   5. 평가 대상 목록 조회 (자기 자신이 빠지는지)
#   6. B, C 가 각각 A 를 평가 → A 가 2건을 받아 온도 공개
#   7. 중복 제출 / 자기 자신 평가 → 차단 확인
#   8. A 의 마이페이지에서 온도 노출 확인
#
# 왜 3명인가: 온도는 받은 평가가 2건 이상이어야 공개된다(2인 팀이면 1건뿐이라 누가 줬는지
# 자명해져 익명성이 깨지므로 비공개). B, C 두 명이 A 를 평가해야 온도가 실제로 뜬다.
param(
    [switch]$KeepTeam   # 지정 시 시나리오 종료 후 팀을 남겨 둔다(기본도 남김 — 삭제는 하지 않음)
)
. "$PSScriptRoot\00_common.ps1"

Write-Host "`n########## 15. Review (협업 온도) - /api/teams/{id}/reviews [인증 필요] ##########" -ForegroundColor Magenta

try {

# --- 선행 조건: 슬롯 토큰 3개 ---
$idA = Get-SlotUserId "A"; $idB = Get-SlotUserId "B"; $idC = Get-SlotUserId "C"
if (-not ($idA -and $idB -and $idC)) {
    Write-Host "(!) 유저 3명의 슬롯 토큰이 필요합니다. 먼저 .\auth\09_three_users.ps1 을 실행하세요." -ForegroundColor Red
    return
}
Write-Host "유저: A(팀장)=$idA, B=$idB, C=$idC" -ForegroundColor Yellow

function Get-LastStatus {
    if ($global:MateonTestResults.Count -eq 0) { return $null }
    return $global:MateonTestResults[$global:MateonTestResults.Count - 1].Status
}

# ============================================================================
#  15.1 팀 생성 (A)
# ============================================================================
Use-User "A" | Out-Null
$created = Invoke-Api -Method POST -Path "/api/teams" -Auth -PassThru -Title "15.1 팀 생성 (팀장 A)" -Body @{
    eventId              = $null
    title                = "협업온도 테스트 팀 $((Get-Random -Maximum 9999))"
    promotionText        = "협업 온도 평가 시나리오 검증용"
    role                 = @("백엔드", "프론트엔드")
    characteristic       = "테스트"
    capacity             = 3
    recruitmentStartDate = (Get-Date).ToString("yyyy-MM-dd")
    recruitmentEndDate   = (Get-Date).AddDays(30).ToString("yyyy-MM-dd")
}
$teamId = $created.data.id
if (-not $teamId) {
    Write-Host "(!) 팀 생성 실패 - 이후 단계를 진행할 수 없습니다." -ForegroundColor Red
    return
}
Write-Host "  (i) teamId=$teamId" -ForegroundColor Green

# 팀 생성 직후 인원은 팀장 1명이어야 한다.
# (team_members 로 옮기기 전에는 지원서 집계에 팀장이 안 잡혀 +1 보정에 의존했다.)
Assert-Test -Title "15.1b 생성 직후 인원 = 1 (팀장)" `
    -Condition ($created.data.currentMemberCount -eq 1) `
    -Detail "currentMemberCount=$($created.data.currentMemberCount)" | Out-Null

# ============================================================================
#  15.2 B, C 지원 → A 가 승인
# ============================================================================
function Submit-Application {
    param([string]$Slot)
    Use-User $Slot | Out-Null
    Invoke-Api -Method POST -Path "/api/teams/$teamId/apply" -Auth -Title "15.2 팀 지원 ($Slot)" -Body @{
        introduction  = "$Slot 지원합니다"
        message       = "협업 온도 테스트"
        contactNumber = "010-0000-0000"
        portfolioUrl  = "https://github.com/example"
    } | Out-Null
}
Submit-Application -Slot "B"
Submit-Application -Slot "C"

# 팀장이 지원서를 전부 승인한다.
Use-User "A" | Out-Null
$teamApps = Invoke-Api -Method GET -Path "/api/teams/$teamId/applications" -Auth -PassThru -Title "15.3 지원서 목록 (팀장 A)"
foreach ($app in @($teamApps.data)) {
    # 필드명은 id 가 아니라 applicationId 다 (TeamApplicationResponseDTO).
    Invoke-Api -Method PATCH -Path "/api/teams/applications/$($app.applicationId)`?isApproved=true" -Auth `
        -Title "15.3b 지원 승인 (applicationId=$($app.applicationId))" | Out-Null
}

$detail = Invoke-Api -Method GET -Path "/api/teams/$teamId" -Auth -PassThru -Title "15.4 팀 상세 (인원 확인)"
Assert-Test -Title "15.4b 승인 후 인원 = 3 (팀장+2명)" `
    -Condition ($detail.data.currentMemberCount -eq 3) `
    -Detail "currentMemberCount=$($detail.data.currentMemberCount)" | Out-Null

# ============================================================================
#  15.5 종료 전에는 평가할 수 없다
# ============================================================================
Use-User "B" | Out-Null
Invoke-Api -Method GET -Path "/api/teams/$teamId/reviews/targets" -Auth `
    -Title "15.5 종료 전 평가 대상 조회 (차단 기대)" | Out-Null

Invoke-Api -Method POST -Path "/api/teams/$teamId/reviews" -Auth -Title "15.5b 종료 전 평가 제출 (차단 기대)" -Body @{
    reviews = @(@{ revieweeId = [int]$idA; rating = 5 })
} | Out-Null

# ============================================================================
#  15.6 팀장이 아니면 종료할 수 없다
# ============================================================================
Invoke-Api -Method POST -Path "/api/teams/$teamId/complete" -Auth `
    -Title "15.6 팀원 B 의 활동 종료 (차단 기대)" | Out-Null

# ============================================================================
#  15.7 팀장 A 가 활동 종료 → 평가 개시
# ============================================================================
# 종료 전에 B 의 '팀원 평가 요청' 알림 수를 기록해 둔다. 15.7d 에서 증분으로 검증하기 위함이다
# (15.13 의 협업 온도와 같은 이유 — 누적 데이터라 절대값으로는 이번 실행의 결과를 알 수 없다).
Use-User "B" | Out-Null
$notiBefore = Invoke-Api -Method GET -Path "/api/notifications" -Auth -PassThru -NoTrack
$reviewNotiBefore = @($notiBefore.data | Where-Object { $_.title -eq "팀원 평가 요청" }).Count

Use-User "A" | Out-Null
Invoke-Api -Method POST -Path "/api/teams/$teamId/complete" -Auth -Title "15.7 활동 종료 (팀장 A)" | Out-Null
$completeStatus = Get-LastStatus
Assert-Test -Title "15.7b 활동 종료 성공(2xx)" `
    -Condition ($completeStatus -and [int]$completeStatus -lt 400) `
    -Detail "status=$completeStatus" | Out-Null

# 이미 종료된 팀은 다시 종료할 수 없다.
Invoke-Api -Method POST -Path "/api/teams/$teamId/complete" -Auth `
    -Title "15.7c 중복 종료 (차단 기대)" | Out-Null

# ----------------------------------------------------------------------------
# 15.7d 종료 알림이 실제로 남는지 확인 (회귀 방지)
#
# 예전엔 SSE 전송 실패가 알림 저장 트랜잭션을 롤백시켜, 종료 API 는 200 인데 알림이
# 통째로 사라졌다. 종료 호출의 HTTP 상태만 보던 15.7b 로는 이걸 못 잡았다.
#
# 재현 조건: 06_notification.ps1 이 SSE 를 5초 후 강제로 끊어 죽은 emitter 를 남긴다.
#            99_run_all.ps1 에서 06 이 15 보다 먼저 돌아야 하므로 실행 순서를 바꾸지 말 것.
# TeamCompletedNotificationListener 는 @Async + AFTER_COMMIT 이라 잠깐 기다린다.
#
# 반드시 '증분'으로 본다. 알림은 계속 쌓이므로 절대 개수로 보면 이전 실행이 남긴 알림 때문에
# 이번 종료가 통째로 롤백돼도 통과해버린다.
# ----------------------------------------------------------------------------
Use-User "B" | Out-Null
Start-Sleep -Seconds 2
$noti = Invoke-Api -Method GET -Path "/api/notifications" -Auth -PassThru -NoTrack
$reviewNotiAfter = @($noti.data | Where-Object { $_.title -eq "팀원 평가 요청" }).Count
Assert-Test -Title "15.7d 팀 종료 시 평가 요청 알림 수신 (B)" `
    -Condition ($reviewNotiAfter -gt $reviewNotiBefore) `
    -Detail "$reviewNotiBefore → $reviewNotiAfter (증분 기대)" | Out-Null

# 아래 15.8 은 'A 입장' 이므로 계정을 되돌려 놓는다.
Use-User "A" | Out-Null

# ============================================================================
#  15.8 평가 대상 목록 — 자기 자신은 빠진다
# ============================================================================
$targets = Invoke-Api -Method GET -Path "/api/teams/$teamId/reviews/targets" -Auth -PassThru `
    -Title "15.8 평가 대상 목록 (A 입장)"
$targetIds = @($targets.data.targets | ForEach-Object { [string]$_.userId })

Assert-Test -Title "15.8b A 의 평가 대상은 B, C 두 명" `
    -Condition ($targetIds.Count -eq 2) `
    -Detail "targets=$($targetIds -join ', ')" | Out-Null
Assert-Test -Title "15.8c 대상 목록에 자기 자신(A) 없음" `
    -Condition (-not ($targetIds -contains [string]$idA)) `
    -Detail "targets=$($targetIds -join ', ')" | Out-Null
Assert-Test -Title "15.8d 평가 마감 시각(reviewDeadline) 내려옴" `
    -Condition ([bool]$targets.data.reviewDeadline) `
    -Detail "endedAt=$($targets.data.endedAt), deadline=$($targets.data.reviewDeadline)" | Out-Null

# ============================================================================
#  15.9 자기 자신 평가 → 차단
# ============================================================================
Invoke-Api -Method POST -Path "/api/teams/$teamId/reviews" -Auth -Title "15.9 자기 자신 평가 (차단 기대)" -Body @{
    reviews = @(@{ revieweeId = [int]$idA; rating = 5 })
} | Out-Null

# ============================================================================
#  15.10 B, C 가 각각 A 를 평가 → A 가 2건을 더 받는다
#
#  [중요] 협업 온도는 유저 단위 '누적' 평판이다. UNIQUE 제약은 (team_id, reviewer_id, reviewee_id)
#  라서 팀이 다르면 같은 사람이 다시 평가할 수 있고, 이 스크립트는 실행마다 새 팀을 만든다.
#  즉 2회차 실행부터 A 의 평가 수는 2, 4, 6... 으로 늘어난다.
#  그래서 절대값(=2, =38.1)이 아니라 '실행 전 대비 증분'으로 검증해야 한다.
# ============================================================================
# 평가 전 A 의 누적 상태를 기록해 둔다.
Use-User "A" | Out-Null
$before = Invoke-Api -Method GET -Path "/api/users/mypage" -Auth -PassThru -NoTrack
$beforeCount = [int]$before.data.collaborationReviewCount
$beforeTemp  = $before.data.collaborationTemperature
Write-Host "  (i) 평가 전 A: reviewCount=$beforeCount, temperature=$beforeTemp" -ForegroundColor DarkGray

foreach ($slot in @("B", "C")) {
    Use-User $slot | Out-Null
    Invoke-Api -Method POST -Path "/api/teams/$teamId/reviews" -Auth -Title "15.10 평가 제출 ($slot → A, 5점)" -Body @{
        reviews = @(@{ revieweeId = [int]$idA; rating = 5 })
    } | Out-Null
    $s = Get-LastStatus
    Assert-Test -Title "15.10b $slot 평가 제출 성공(2xx)" `
        -Condition ($s -and [int]$s -lt 400) -Detail "status=$s" | Out-Null
}

# 같은 대상을 두 번 평가할 수 없다 (팀당 쌍당 1회, DB UNIQUE 로 강제).
Use-User "B" | Out-Null
Invoke-Api -Method POST -Path "/api/teams/$teamId/reviews" -Auth -Title "15.11 중복 평가 (차단 기대)" -Body @{
    reviews = @(@{ revieweeId = [int]$idA; rating = 1 })
} | Out-Null

# 제출한 대상은 '완료'로 표시된다.
$afterB = Invoke-Api -Method GET -Path "/api/teams/$teamId/reviews/targets" -Auth -PassThru `
    -Title "15.12 제출 후 대상 목록 (B 입장)"
$aEntry = @($afterB.data.targets | Where-Object { [string]$_.userId -eq [string]$idA })[0]
Assert-Test -Title "15.12b B 가 평가한 A 는 alreadyReviewed=true" `
    -Condition ([bool]$aEntry.alreadyReviewed) `
    -Detail "alreadyReviewed=$($aEntry.alreadyReviewed)" | Out-Null

# ============================================================================
#  15.13 온도 노출 확인 (누적 증분 기준)
# ============================================================================
Use-User "A" | Out-Null
$myPage = Invoke-Api -Method GET -Path "/api/users/mypage" -Auth -PassThru -Title "15.13 마이페이지 협업 온도 (A)"
$temp  = $myPage.data.collaborationTemperature
$count = [int]$myPage.data.collaborationReviewCount

Write-Host "`n  [협업 온도] A: temperature=$temp, reviewCount=$count (실행 전: $beforeCount / $beforeTemp)" -ForegroundColor Yellow

Assert-Test -Title "15.13b A 의 평가 수가 2 증가" `
    -Condition ($count -eq $beforeCount + 2) `
    -Detail "$beforeCount → $count" | Out-Null

Assert-Test -Title "15.13c A 의 온도가 공개됨(비공개 임계 2건 충족)" `
    -Condition ($null -ne $temp) -Detail "temperature=$temp" | Out-Null

if ($beforeCount -eq 0) {
    # 첫 실행(깨끗한 계정)에서만 절대값을 검증할 수 있다.
    #   B=(5*3+10)/(5+2)=3.571 → q=0.286 → E=2/22=0.0909 → 36.5 + 62.5*0.286*0.0909 = 38.1
    Assert-Test -Title "15.13d 첫 평가 2건(5점)의 온도 = 38.1" `
        -Condition ([math]::Abs([double]$temp - 38.1) -lt 0.05) `
        -Detail "temperature=$temp (기대 38.1)" | Out-Null
} else {
    # 재실행: 5점을 더 받았으니 온도는 반드시 올라야 한다.
    # (5점은 베이지안 평균을 항상 끌어올리고, 건수가 늘어 신뢰도 계수도 커진다.)
    Assert-Test -Title "15.13d 5점을 더 받아 온도 상승" `
        -Condition ([double]$temp -gt [double]$beforeTemp) `
        -Detail "$beforeTemp → $temp" | Out-Null
    Write-Host "  (i) 누적 계정이라 절대값 검증은 건너뜁니다. 38.1 을 다시 보려면 새 계정으로 .env 를 바꾸세요." -ForegroundColor DarkGray
}

# 평가를 받지 않은 B 는 온도가 비공개(null)여야 한다.
Use-User "B" | Out-Null
$myPageB = Invoke-Api -Method GET -Path "/api/users/mypage" -Auth -PassThru -Title "15.14 미평가 유저 온도 (B)"
Assert-Test -Title "15.14b 평가를 못 받은 B 는 온도 비공개(null)" `
    -Condition ($null -eq $myPageB.data.collaborationTemperature) `
    -Detail "temperature=$($myPageB.data.collaborationTemperature), count=$($myPageB.data.collaborationReviewCount)" | Out-Null

# --- 정리 ---
Use-User "A" | Out-Null
Write-Host "`n[정리] teamId=$teamId 는 남겨 둡니다(종료 상태). 활성 세션 = 유저 A." -ForegroundColor DarkGray

} finally {
    Write-TestSummary | Out-Null
}

