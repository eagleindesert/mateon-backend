# 08_social_kakao.ps1 - 카카오 소셜 로그인/회원가입 테스트  /api/auth/social/kakao
# 사용법:
#   powershell -ExecutionPolicy Bypass -File .\auth\08_social_kakao.ps1
#   powershell -ExecutionPolicy Bypass -File .\auth\08_social_kakao.ps1 -KakaoAccessToken <실제_카카오_토큰>
#
# [토큰 두 종류 구분]
#   (A) 카카오 액세스 토큰 : RN/카카오가 발급 → 우리가 '입력'으로 받는 값 ($KakaoAccessToken).
#                            요청 본문 accessToken 필드로 백엔드에 넘긴다.
#   (B) 우리 서비스 JWT     : 백엔드가 카카오 로그인 성공 후 '발급'해 응답으로 주는 값
#                            ($kakao.data.accessToken / refreshToken). 이후 우리 API 인증에 쓴다.
#
# C단계(소셜 진입점 실제 연결) 검증:
#   - 음성 경로(항상 실행): 잘못된 (A)카카오 토큰 → KAKAO_AUTH_FAILED(4xx) 확인.
#       (실제 카카오 토큰 없이 검증 가능한 핵심 경로)
#   - 정상 경로(선택): -KakaoAccessToken(또는 .env 의 MATEON_KAKAO_ACCESS_TOKEN)이 주어지면
#       실제 (A)카카오 로그인 → (B)서비스 JWT 발급/JWT subject(userId) 확인 → /me 의
#       schoolVerified=false (신규 카카오 유저) 확인 → 팀 생성이 학교인증 게이팅(4xx)되는지까지 확인.
param(
    [string]$KakaoAccessToken   # 미지정 시 00_common 의 KakaoAccessToken(.env/env) 사용
)
. "$PSScriptRoot\..\00_common.ps1"

# param 미지정 시 00_common 의 기본값(대개 .env 의 MATEON_KAKAO_ACCESS_TOKEN)으로 채운다.
if (-not $KakaoAccessToken) { $KakaoAccessToken = $script:KakaoAccessToken }

Write-Host "`n########## 8. Social Login (Kakao) - /api/auth/social/kakao ##########" -ForegroundColor Magenta

try {

# 마지막 API 호출의 HTTP 상태(문자열)를 집계 트래커에서 읽어온다.
function Get-LastStatus {
    if ($global:MateonTestResults.Count -eq 0) { return $null }
    return $global:MateonTestResults[$global:MateonTestResults.Count - 1].Status
}

# --- 1) 음성 테스트: 잘못된 (A)카카오 토큰 → 4xx (KAKAO_AUTH_FAILED) ---
Write-Host "`n[8.1 잘못된 카카오 액세스 토큰 → 인증 실패 기대]" -ForegroundColor Cyan
Invoke-Api -Method POST -Path "/api/auth/social/kakao" -Title "8.1 (invalid) 카카오 로그인 시도 → 차단 기대" -Body @{
    accessToken = "invalid-token"   # (A) 카카오 액세스 토큰 자리에 일부러 잘못된 값
} | Out-Null
$invalidStatus = Get-LastStatus
Assert-Test -Title "8.1 잘못된 토큰 카카오 로그인 차단(4xx)" `
    -Condition ($invalidStatus -and [int]$invalidStatus -ge 400) `
    -Detail "status=$invalidStatus (KAKAO_AUTH_FAILED 기대)" | Out-Null

# --- 2) 정상 경로(선택): 실제 카카오 토큰이 있을 때만 ---
if (-not $KakaoAccessToken) {
    Write-Host "`n[8.2 실제 카카오 로그인] 스킵 - 실제 토큰이 없습니다." -ForegroundColor Yellow
    Write-Host "     -KakaoAccessToken <토큰> 또는 .env 의 MATEON_KAKAO_ACCESS_TOKEN 을 지정하면 정상 경로까지 검증합니다." -ForegroundColor Yellow
    return
}

Write-Host "`n[8.2 실제 카카오 로그인 → 서비스 JWT 발급]" -ForegroundColor Cyan
# 입력: (A) 카카오 액세스 토큰 → 요청 본문 accessToken 필드
$kakao = Invoke-Api -Method POST -Path "/api/auth/social/kakao" -PassThru -Title "8.2 카카오 로그인/회원가입" -Body @{
    accessToken = $KakaoAccessToken   # (A) 카카오 액세스 토큰
}

# 응답: (B) 우리 서비스 JWT (access/refresh). (A)카카오 토큰과 헷갈리지 않도록 별도 변수에 담는다.
$jwtAccessToken  = $kakao.data.accessToken
$jwtRefreshToken = $kakao.data.refreshToken

Assert-Test -Title "8.2 서비스 JWT 발급(accessToken)" `
    -Condition ([bool]$jwtAccessToken) `
    -Detail "서비스 JWT accessToken 존재=$([bool]$jwtAccessToken)" | Out-Null

if ($jwtAccessToken) {
    # 이후 우리 API 인증에는 (B)서비스 JWT 를 저장해 재사용한다. (A)카카오 토큰은 여기서 역할 끝.
    Save-AccessToken $jwtAccessToken
    Save-RefreshToken $jwtRefreshToken
    Write-Host "  (i) 서비스 JWT 저장 완료 -> 이후 인증 필요 테스트에서 재사용" -ForegroundColor Green

    # 2b) (B)서비스 JWT 의 subject 가 userId(숫자)인지 확인
    $sub = Get-JwtSubject -Token $jwtAccessToken
    Assert-Test -Title "8.2b 서비스 JWT subject 가 userId(숫자)" -Condition ($sub -match '^\d+$') -Detail "sub=$sub" | Out-Null
}

# --- 3) 신규 카카오 유저는 학교 미인증(schoolVerified=false) ---
$me = Invoke-Api -Method GET -Path "/api/users/me" -Auth -PassThru -Title "8.3 프로필 schoolVerified 확인"
Assert-Test -Title "8.3 신규 카카오 유저 schoolVerified=false" `
    -Condition (-not [bool]$me.data.schoolVerified) `
    -Detail "schoolVerified=$($me.data.schoolVerified), email=$($me.data.email)" | Out-Null

# --- 4) 미인증 상태이므로 학생 전용 쓰기(팀 생성)는 게이팅되어야 함 ---
Write-Host "`n[8.4 미인증 카카오 유저 → 팀 생성 게이팅]" -ForegroundColor Cyan
$teamBody = @{
    eventId              = $null
    title                = "카카오 게이팅 테스트 팀 $((Get-Random -Maximum 9999))"
    promotionText        = "게이팅 검증용 팀"
    role                 = @("백엔드")
    characteristic       = "테스트"
    capacity             = 4
    recruitmentStartDate = (Get-Date).ToString("yyyy-MM-dd")
    recruitmentEndDate   = (Get-Date).AddDays(30).ToString("yyyy-MM-dd")
}
Invoke-Api -Method POST -Path "/api/teams" -Auth -Title "8.4 (미인증) 팀 생성 시도 → 차단 기대" -Body $teamBody | Out-Null
$gatedStatus = Get-LastStatus
Assert-Test -Title "8.4 미인증 카카오 유저 팀 생성 차단(4xx)" `
    -Condition ($gatedStatus -and [int]$gatedStatus -ge 400) `
    -Detail "status=$gatedStatus (SCHOOL_NOT_VERIFIED 기대)" | Out-Null

} finally {
    Write-TestSummary | Out-Null
}
