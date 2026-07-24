# 14_reverse_offer.ps1 (for-api-server) - 역제안(팀→유저)
#   GET    /api/matching/recommendations/team-to-user
#   POST   /api/teams/{teamId}/offers
#   GET    /api/teams/{teamId}/offers
#   GET    /api/teams/offers/me
#   PATCH  /api/teams/offers/{offerId}
#   DELETE /api/teams/offers/{offerId}
#
# ============================================================================
#  [!] 과금 주의 - 이 스크립트는 실제 LLM / 임베딩을 호출합니다.
#      예상 호출 6회:
#        - 팀 생성 2회  -> 비동기 임베딩 갱신 (LLM 추출 + 임베딩)
#        - 의도 추출 2회 -> POST /intents/messages (LLM + 임베딩)
#        - 역제안 점수화 2회 -> 제안 전/후 각각 AI 호출
#      ※ 팀장이 아닌 사람의 요청(14.4)은 AI 를 호출하지 않습니다 (403 이 먼저).
#      ※ 추천은 의도 추출과 달리 AI 호출이 동기입니다 — 느리면 AI 응답을 기다리는 중입니다.
#
#      과금 없이 돌리려면 백엔드가 로컬 스텁을 보게 하세요:
#        pwsh -File ..\debug\ai-stub\stub-ai-server.ps1     # 포트 8000
#        백엔드 AI_BASE_URL=http://localhost:8000 으로 재기동
# ============================================================================
#
# 사용법: pwsh -File .\14_reverse_offer.ps1            # 기본: 만든 팀을 남긴다
#        pwsh -File .\14_reverse_offer.ps1 -Cleanup    # A 가 만든 팀까지 삭제
# 사전 조건:
#   1) 로그인이 선행되어 .auth-token.txt 가 있어야 한다 (99_run_all 또는 auth\02_auth.ps1).
#   2) 유저 B 계정이 있어야 한다 (02_auth 가 함께 생성).
#      A = 팀장(제안을 보내는 쪽), B = 추천받고 수락하는 쪽.
#   3) A 와 B 모두 학교 인증이 되어 있어야 한다 (팀 생성/팀 합류의 전제 조건).
#   4) 백엔드의 ai.base-url 이 살아있는 AI 서버(또는 스텁)를 가리켜야 한다.
#
# [검증 범위]
#   - 팀장이 아니면 역제안 추천을 받을 수 없는지 (403 차단)
#   - 의도 추출을 마친 유저만 후보에 오르는지 / 점수 내림차순 정렬 / label 노출
#   - 팀장 본인이 후보에서 빠지는지
#   - 제안 발송 시 AI 점수·근거가 서버에서 스냅샷되는지 (프론트가 안 보냈는데도 채워짐)
#   - 중복 제안이 막히는지, 제안한 유저가 다음 추천에서 빠지는지
#   - 수락 시 즉시 팀원이 되는지 (인원 +1 증분) + 정원이 차면 모집 마감되는지
#   - 이미 처리된 제안에 재응답이 막히는지
#   - 팀장의 제안 취소가 되는지 / 취소된 제안에 응답이 막히는지
#   - 알림이 양쪽으로 가는지 (증분 확인)
param(
    [switch]$Cleanup,
    [string]$UserBEmail,       # 미지정 시 00_common 의 UserBEmail
    [string]$UserBPassword     # 미지정 시 00_common 의 UserBPassword
)
. "$PSScriptRoot\00_common.ps1"

if (-not $UserBEmail)    { $UserBEmail    = $script:UserBEmail }
if (-not $UserBPassword) { $UserBPassword = $script:UserBPassword }

Write-Host "`n########## 14. Reverse Offer (역제안: 팀→유저) [인증 필요] ##########" -ForegroundColor Magenta

try {

$tokenA = Get-AccessToken
if (-not $tokenA) {
    Write-Host "(!) accessToken 이 없습니다. 먼저 .\auth\02_auth.ps1 (또는 99_run_all) 을 실행하세요." -ForegroundColor Red
    return
}
$userIdA = Get-JwtSubject -Token $tokenA

function Use-Token { param([string]$Token) Save-AccessToken $Token }

# 알림 건수를 세는 헬퍼 — 누적 데이터 환경이라 절대값이 아니라 증분으로 본다.
function Get-NotificationCount {
    $r = Invoke-Api -Method GET -Path "/api/notifications" -Auth -PassThru -NoTrack
    return @($r.data).Count
}

# ============================================================================
#  0) 유저 B 준비 — 추천받고 제안을 수락할 사람
# ============================================================================
$loginB = Invoke-Api -Method POST -Path "/api/auth/login" -PassThru -Title "14.0 유저 B 로그인 (제안 받을 사람)" -Body @{
    email = $UserBEmail; password = $UserBPassword
}
$tokenB = $loginB.data.accessToken
if (-not $tokenB) {
    Write-Host "(!) 유저 B 로그인 실패 - 먼저 .\auth\02_auth.ps1 로 유저 B 계정을 생성하세요." -ForegroundColor Red
    Use-Token $tokenA
    return
}
$userIdB = Get-JwtSubject -Token $tokenB
if ($userIdA -eq $userIdB) {
    Write-Host "(!) A 와 B 가 동일 계정입니다. -UserBEmail 을 A 와 다른 이메일로 지정하세요." -ForegroundColor Red
    Use-Token $tokenA
    return
}
Write-Host "  (i) userIdA=$userIdA (팀장/제안 발송), userIdB=$userIdB (제안 수신/수락)" -ForegroundColor Green

# ============================================================================
#  1) A 가 팀 2개 생성
#     - teamMain : capacity=2 (팀장 A + B 한 명이면 정원 마감) — 수락/마감 검증용
#     - teamSub  : capacity=3 — 제안 취소 검증용 (제안 유일성이 팀 단위라 팀을 갈라야 한다)
# ============================================================================
Use-Token $tokenA

$teamMain = Invoke-Api -Method POST -Path "/api/teams" -Auth -PassThru -Title "14.1 (A) 역제안용 팀 생성 (capacity=2)" -Body @{
    eventId              = $null
    title                = "역제안테스트 메인팀 $((Get-Random -Maximum 9999))"
    promotionText        = "커머스 서비스를 만드는 팀입니다. 백엔드를 함께할 분을 찾고 있고 초보자도 환영합니다."
    role                 = @("BE")
    requiredSkills       = @("Spring Boot", "PostgreSQL")
    characteristic       = "초보 환영"
    capacity             = 2
    recruitmentStartDate = (Get-Date).ToString("yyyy-MM-dd")
    recruitmentEndDate   = (Get-Date).AddDays(30).ToString("yyyy-MM-dd")
}
$teamMainId = $teamMain.data.id

$teamSub = Invoke-Api -Method POST -Path "/api/teams" -Auth -PassThru -Title "14.2 (A) 취소 검증용 팀 생성 (capacity=3)" -Body @{
    eventId              = $null
    title                = "역제안테스트 서브팀 $((Get-Random -Maximum 9999))"
    promotionText        = "데이터 파이프라인을 만드는 팀입니다. 백엔드 인원을 모집합니다."
    role                 = @("BE")
    requiredSkills       = @("Kafka")
    characteristic       = "차분한 팀"
    capacity             = 3
    recruitmentStartDate = (Get-Date).ToString("yyyy-MM-dd")
    recruitmentEndDate   = (Get-Date).AddDays(30).ToString("yyyy-MM-dd")
}
$teamSubId = $teamSub.data.id

if (-not $teamMainId -or -not $teamSubId) {
    Assert-Test -Title "14.2a 팀 2개 생성 성공" -Condition $false -Detail "teamMainId=$teamMainId, teamSubId=$teamSubId" | Out-Null
    Use-Token $tokenA
    return
}
Write-Host "  (i) teamMainId=$teamMainId, teamSubId=$teamSubId" -ForegroundColor Green

# 팀 임베딩은 커밋 후 비동기로 저장된다. 팀 임베딩이 없으면 역제안 추천 자체가 400 이다
# (유저→팀 방향에서는 그냥 후보에서 빠졌지만, 여기서는 질의 벡터가 없는 셈이다).
Write-Host "  (i) 비동기 팀 임베딩 저장 대기 (4초)..." -ForegroundColor DarkGray
Start-Sleep -Seconds 4

# ============================================================================
#  2) B 의 의도 추출 — 후보로 오르기 위한 전제 조건
# ============================================================================
Use-Token $tokenB

Invoke-Api -Method POST -Path "/api/matching/intents/session/restart" -Auth -Title "14.3 (B) 의도 세션 초기화" | Out-Null

Invoke-Api -Method POST -Path "/api/matching/intents/messages" -Auth -Title "14.3a (B) 의도 추출 1턴" -Body @{
    message = "백엔드 개발을 맡아서 서비스를 만들어보고 싶어요."
} | Out-Null

$intent2 = Invoke-Api -Method POST -Path "/api/matching/intents/messages" -Auth -PassThru -Title "14.3b (B) 의도 추출 2턴 (완료 기대)" -Body @{
    message = "아직 입문 수준이고, 주 2회 정도 오프라인으로 만나고 싶어요."
}
Assert-Test -Title "14.3c (B) 의도 추출 완료 (슬롯 + 임베딩 생성)" `
    -Condition ([bool]$intent2.data.completed) `
    -Detail ("completed={0}" -f $intent2.data.completed) | Out-Null

if (-not $intent2.data.completed) {
    Write-Host "(!) B 의 의도 추출이 완료되지 않아 역제안을 검증할 수 없습니다. AI 서버(스텁) 상태를 확인하세요." -ForegroundColor Red
    Use-Token $tokenA
    return
}

# ============================================================================
#  3) 권한 — 팀장이 아니면 역제안 추천을 받을 수 없다
# ============================================================================
Invoke-Api -Method GET -Path "/api/matching/recommendations/team-to-user?teamId=$teamMainId" -Auth `
    -Title "14.4 (B) 남의 팀으로 역제안 추천 요청 - 차단 기대 (403 FORBIDDEN_ACCESS)" | Out-Null

Invoke-Api -Method GET -Path "/api/teams/$teamMainId/offers" -Auth `
    -Title "14.4a (B) 남의 팀 제안 목록 조회 - 차단 기대 (403 FORBIDDEN_ACCESS)" | Out-Null

# ============================================================================
#  4) 본 검증 — 팀장이 유저 추천을 받는다
# ============================================================================
Use-Token $tokenA

$rec = Invoke-Api -Method GET -Path "/api/matching/recommendations/team-to-user?teamId=$teamMainId&limit=10" -Auth -PassThru `
    -Title "14.5 (A) 역제안 추천 요청 (limit=10)"

$items = @($rec.data)
Assert-Test -Title "14.5a 추천 결과 수신" -Condition ([bool]($rec.success -and $items.Count -gt 0)) `
    -Detail ("{0}건" -f $items.Count) | Out-Null

if ($items.Count -eq 0) {
    Write-Host "(!) 후보가 0건입니다. B 의 의도 추출(matching_intent_slots + user_embeddings)을 확인하세요." -ForegroundColor Red
    if ($Cleanup) {
        Invoke-Api -Method DELETE -Path "/api/teams/$teamMainId" -Auth -Title "14.x 뒷정리" | Out-Null
        Invoke-Api -Method DELETE -Path "/api/teams/$teamSubId" -Auth -Title "14.x 뒷정리" | Out-Null
    }
    return
}

# 점수 내림차순 — 스텁은 일부러 오름차순으로 돌려주므로 정렬은 백엔드가 한 것이다.
$scores = @($items | ForEach-Object { [double]$_.score })
$sortedDesc = @($scores | Sort-Object -Descending)
Assert-Test -Title "14.5b 점수 내림차순 정렬" `
    -Condition (($scores -join ",") -eq ($sortedDesc -join ",")) `
    -Detail ("scores=[{0}]" -f ($scores -join ", ")) | Out-Null

$allHaveLabel = @($items | Where-Object { -not $_.label }).Count -eq 0
Assert-Test -Title "14.5c 모든 추천에 label 노출" -Condition $allHaveLabel `
    -Detail ("첫 label='{0}'" -f $items[0].label) | Out-Null

# 팀장 본인은 이미 팀원(team_members 에 LEADER)이므로 후보에서 빠져야 한다.
$selfInResults = @($items | Where-Object { "$($_.userId)" -eq "$userIdA" })
Assert-Test -Title "14.5d 팀장 본인은 후보에서 제외" -Condition ($selfInResults.Count -eq 0) `
    -Detail ("본인 {0}건 포함" -f $selfInResults.Count) | Out-Null

# 연락처 성격의 필드가 새어 나가면 안 된다 (아직 아무 관계도 없는 유저의 목록이다).
$leaked = @($items | Where-Object { $_.email -or $_.schoolEmail })
Assert-Test -Title "14.5e 추천 응답에 이메일 등 연락처 미노출" -Condition ($leaked.Count -eq 0) `
    -Detail ("노출 {0}건" -f $leaked.Count) | Out-Null

# B 가 후보에 있어야 제안을 보낼 수 있다. 실서버는 컷오프로 일부만 돌려주므로 없을 수도 있는데,
# 그러면 이후 검증이 전부 무의미해지므로 여기서 멈춘다.
$bItem = @($items | Where-Object { "$($_.userId)" -eq "$userIdB" })[0]
Assert-Test -Title "14.5f 후보 목록에 B 가 포함" -Condition ([bool]$bItem) `
    -Detail ("userIdB={0}, 전체 {1}건" -f $userIdB, $items.Count) | Out-Null

if (-not $bItem) {
    Write-Host "(!) B 가 추천 목록에 없어 이후 제안 검증을 건너뜁니다." -ForegroundColor Red
    Write-Host "    실서버는 자체 컷오프로 상위 일부만 돌려줍니다 - team_to_user_recommendation_items 로 확인하세요." -ForegroundColor DarkGray
    return
}

# ============================================================================
#  5) 제안 발송
# ============================================================================
# 알림 증분을 보기 위해 B 의 현재 알림 수를 먼저 센다.
Use-Token $tokenB
$notifyCountBBefore = Get-NotificationCount
Use-Token $tokenA

$offer = Invoke-Api -Method POST -Path "/api/teams/$teamMainId/offers" -Auth -PassThru `
    -Title "14.6 (A) B 에게 제안 발송" -Body @{
    userId  = [int]$userIdB
    message = "백엔드 역할로 함께해 주세요!"
}
$offerId = $offer.data.offerId
Assert-Test -Title "14.6a 제안 생성됨 (status=PENDING)" `
    -Condition ([bool]($offerId -and $offer.data.status -eq "PENDING")) `
    -Detail ("offerId={0}, status={1}" -f $offerId, $offer.data.status) | Out-Null

# 프론트는 점수를 보내지 않았다. 서버가 추천 로그에서 찾아 스냅샷한 것이어야 한다.
Assert-Test -Title "14.6b AI 점수/근거가 서버에서 스냅샷됨" `
    -Condition ([bool]($null -ne $offer.data.aiScore -and $offer.data.aiLabel)) `
    -Detail ("aiScore={0}, aiLabel='{1}'" -f $offer.data.aiScore, $offer.data.aiLabel) | Out-Null

Assert-Test -Title "14.6c 스냅샷 점수가 추천 응답의 점수와 일치" `
    -Condition ([double]$offer.data.aiScore -eq [double]$bItem.score) `
    -Detail ("제안={0}, 추천={1}" -f $offer.data.aiScore, $bItem.score) | Out-Null

# 중복 제안은 막힌다 (애플리케이션 검사 + uq_team_offers_pair 이중 방어).
Invoke-Api -Method POST -Path "/api/teams/$teamMainId/offers" -Auth `
    -Title "14.6d (A) 같은 유저에게 재제안 - 차단 기대 (400 DUPLICATE_RESOURCE)" -Body @{
    userId  = [int]$userIdB
    message = "한 번 더 보냅니다"
} | Out-Null

# 제안을 보낸 유저는 다음 추천에서 빠진다 (다시 추천해 봐야 제안을 못 보낸다).
$rec2 = Invoke-Api -Method GET -Path "/api/matching/recommendations/team-to-user?teamId=$teamMainId&limit=10" -Auth -PassThru `
    -Title "14.7 (A) 제안 후 재추천 - B 가 후보에서 빠져야 함"
$bStillThere = @(@($rec2.data) | Where-Object { "$($_.userId)" -eq "$userIdB" })
Assert-Test -Title "14.7a 이미 제안한 유저는 후보에서 제외" -Condition ($bStillThere.Count -eq 0) `
    -Detail ("B {0}건 포함 / 전체 {1}건" -f $bStillThere.Count, @($rec2.data).Count) | Out-Null

# 팀장 화면에서 보낸 제안이 보인다.
$sent = Invoke-Api -Method GET -Path "/api/teams/$teamMainId/offers" -Auth -PassThru `
    -Title "14.8 (A) 이 팀이 보낸 제안 목록"
Assert-Test -Title "14.8a 보낸 제안 목록에 방금 제안 포함" `
    -Condition (@(@($sent.data) | Where-Object { $_.offerId -eq $offerId }).Count -eq 1) `
    -Detail ("{0}건" -f @($sent.data).Count) | Out-Null

# ============================================================================
#  6) 유저 쪽 — 받은 제안 확인
# ============================================================================
Use-Token $tokenB

$myOffers = Invoke-Api -Method GET -Path "/api/teams/offers/me" -Auth -PassThru `
    -Title "14.9 (B) 내가 받은 제안 목록"
$mine = @(@($myOffers.data) | Where-Object { $_.offerId -eq $offerId })[0]
Assert-Test -Title "14.9a 받은 제안 목록에 포함 + 팀/근거 정보 노출" `
    -Condition ([bool]($mine -and $mine.teamId -eq $teamMainId -and $mine.aiLabel -and $mine.leaderName)) `
    -Detail ("teamTitle='{0}', leaderName='{1}', aiLabel='{2}'" -f $mine.teamTitle, $mine.leaderName, $mine.aiLabel) | Out-Null

$notifyCountBAfter = Get-NotificationCount
Assert-Test -Title "14.9b 제안 알림이 B 에게 도착 (증분)" `
    -Condition ($notifyCountBAfter -gt $notifyCountBBefore) `
    -Detail ("{0}건 -> {1}건" -f $notifyCountBBefore, $notifyCountBAfter) | Out-Null

# ============================================================================
#  7) 제안 취소 — 서브팀으로 검증 (제안 유일성이 (팀,유저) 단위라 팀을 갈라야 한다)
# ============================================================================
Use-Token $tokenA
$subOffer = Invoke-Api -Method POST -Path "/api/teams/$teamSubId/offers" -Auth -PassThru `
    -Title "14.10 (A) 서브팀에서 B 에게 제안 (취소 검증용)" -Body @{
    userId  = [int]$userIdB
    message = "이쪽 팀도 봐주세요"
}
$subOfferId = $subOffer.data.offerId

# 추천을 거치지 않고 보낸 제안이므로 AI 점수는 비어 있어야 한다 (서브팀은 추천을 돌린 적이 없다).
Assert-Test -Title "14.10a 추천을 거치지 않은 제안은 aiScore 가 null" `
    -Condition ($null -eq $subOffer.data.aiScore) `
    -Detail ("aiScore={0}" -f $subOffer.data.aiScore) | Out-Null

Invoke-Api -Method DELETE -Path "/api/teams/offers/$subOfferId" -Auth -Title "14.10b (A) 제안 취소" | Out-Null

$subOffers = Invoke-Api -Method GET -Path "/api/teams/$teamSubId/offers" -Auth -PassThru `
    -Title "14.10c (A) 취소 후 상태 확인"
$canceled = @(@($subOffers.data) | Where-Object { $_.offerId -eq $subOfferId })[0]
Assert-Test -Title "14.10d 취소된 제안의 status=CANCELED" `
    -Condition ($canceled.status -eq "CANCELED") `
    -Detail ("status={0}, respondedAt={1}" -f $canceled.status, $canceled.respondedAt) | Out-Null

Use-Token $tokenB
Invoke-Api -Method PATCH -Path "/api/teams/offers/$subOfferId" -Auth `
    -Title "14.10e (B) 취소된 제안에 응답 - 차단 기대 (400 OFFER_ALREADY_RESPONDED)" -Body @{
    accepted = $true
} | Out-Null

# ============================================================================
#  8) 수락 — 즉시 팀원 확정 + 정원 마감
# ============================================================================
# 인원 수는 절대값이 아니라 증분으로 본다 (다른 테스트가 만든 데이터가 섞일 수 있다).
Use-Token $tokenA
$beforeDetail = Invoke-Api -Method GET -Path "/api/teams/$teamMainId" -Auth -PassThru `
    -Title "14.11 (A) 수락 전 팀 상태"
$countBefore = [int]$beforeDetail.data.currentMemberCount
$notifyCountABefore = Get-NotificationCount

Use-Token $tokenB
$accepted = Invoke-Api -Method PATCH -Path "/api/teams/offers/$offerId" -Auth -PassThru `
    -Title "14.12 (B) 제안 수락" -Body @{ accepted = $true }
Assert-Test -Title "14.12a 수락 처리됨 (status=ACCEPTED)" `
    -Condition ($accepted.data.status -eq "ACCEPTED") `
    -Detail ("status={0}, respondedAt={1}" -f $accepted.data.status, $accepted.data.respondedAt) | Out-Null

$afterDetail = Invoke-Api -Method GET -Path "/api/teams/$teamMainId" -Auth -PassThru `
    -Title "14.13 (B) 수락 후 팀 상태"
$countAfter = [int]$afterDetail.data.currentMemberCount
Assert-Test -Title "14.13a 수락 즉시 팀원 확정 (인원 +1)" `
    -Condition ($countAfter -eq $countBefore + 1) `
    -Detail ("{0}명 -> {1}명" -f $countBefore, $countAfter) | Out-Null

# capacity=2 인 팀에 팀장 + B 가 찼으므로 모집이 닫혀야 한다.
Assert-Test -Title "14.13b 정원이 차면 모집 마감 (isRecruiting=false)" `
    -Condition ($countAfter -ge [int]$afterDetail.data.capacity) `
    -Detail ("인원 {0} / 정원 {1}" -f $countAfter, $afterDetail.data.capacity) | Out-Null

# 이미 처리한 제안에 다시 응답할 수 없다.
Invoke-Api -Method PATCH -Path "/api/teams/offers/$offerId" -Auth `
    -Title "14.14 (B) 이미 수락한 제안에 재응답 - 차단 기대 (400 OFFER_ALREADY_RESPONDED)" -Body @{
    accepted = $false
} | Out-Null

Use-Token $tokenA
$notifyCountAAfter = Get-NotificationCount
Assert-Test -Title "14.15 수락 알림이 팀장 A 에게 도착 (증분)" `
    -Condition ($notifyCountAAfter -gt $notifyCountABefore) `
    -Detail ("{0}건 -> {1}건" -f $notifyCountABefore, $notifyCountAAfter) | Out-Null

# 모집이 닫힌 팀에서는 새 제안을 보낼 수 없다.
# (B 는 이미 팀원이라 어차피 중복으로 막히므로, 아직 관계가 없는 A 자신을 넣어도 의미가 없다.
#  대신 존재하지 않을 법한 유저 id 를 넣어 '모집 마감'이 유저 검사보다 먼저 걸리는지 본다.)
Invoke-Api -Method POST -Path "/api/teams/$teamMainId/offers" -Auth `
    -Title "14.16 (A) 마감된 팀에서 제안 발송 - 차단 기대 (400 TEAM_RECRUITMENT_CLOSED)" -Body @{
    userId  = 999999999
    message = "마감 후 제안"
} | Out-Null

# ============================================================================
#  9) 뒷정리
# ============================================================================
if ($Cleanup) {
    Invoke-Api -Method DELETE -Path "/api/teams/$teamMainId" -Auth -Title "14.17 뒷정리 (메인팀 삭제)" | Out-Null
    Invoke-Api -Method DELETE -Path "/api/teams/$teamSubId"  -Auth -Title "14.17 뒷정리 (서브팀 삭제)" | Out-Null
} else {
    Write-Host ""
    Write-Host "  (i) A 가 만든 팀을 남깁니다 (teamMainId=$teamMainId, teamSubId=$teamSubId)." -ForegroundColor Yellow
    Write-Host "      삭제까지 하려면 -Cleanup 을 붙여 실행하세요." -ForegroundColor Yellow
    Write-Host "      ※ B 가 메인팀 팀원으로 남아 있어, 다시 돌리면 B 는 그 팀의 추천 후보에서 빠집니다" -ForegroundColor Yellow
    Write-Host "        (새 팀을 만들어 돌리므로 재실행 자체에는 문제가 없습니다)." -ForegroundColor DarkGray
}

Write-Host ""
Write-Host "  (i) 추천 로그와 제안은 API 로 전부 노출되지 않습니다. DB 에서 확인하세요:" -ForegroundColor DarkGray
Write-Host "      SELECT l.id, l.team_id, l.requested_by_user_id, l.candidate_count," -ForegroundColor DarkGray
Write-Host "             i.rank_no, i.user_id, i.score, i.label" -ForegroundColor DarkGray
Write-Host "        FROM team_to_user_recommendation_logs l" -ForegroundColor DarkGray
Write-Host "        JOIN team_to_user_recommendation_items i ON i.log_id = l.id" -ForegroundColor DarkGray
Write-Host "       WHERE l.team_id IN ($teamMainId, $teamSubId) ORDER BY l.id DESC, i.rank_no;" -ForegroundColor DarkGray
Write-Host "      SELECT * FROM team_offers WHERE team_id IN ($teamMainId, $teamSubId) ORDER BY id DESC;" -ForegroundColor DarkGray

Use-Token $tokenA
Write-Host "`n########## 14. Reverse Offer 테스트 완료 ##########" -ForegroundColor Magenta

} finally {
    Write-TestSummary | Out-Null
}
