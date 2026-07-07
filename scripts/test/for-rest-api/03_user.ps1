# 03_user.ps1 - User (사용자) API 테스트  /api/users  [인증 필요]
# 사용법: powershell -ExecutionPolicy Bypass -File .\03_user.ps1
# 사전 조건: 02_auth.ps1 로 로그인하여 .auth-token.txt 가 생성되어 있어야 한다.
. "$PSScriptRoot\_common.ps1"

Write-Host "`n########## 3. User (사용자) - /api/users [인증 필요] ##########" -ForegroundColor Magenta

if (-not (Get-AccessToken)) {
    Write-Host "(!) accessToken 이 없습니다. 먼저 .\02_auth.ps1 을 실행하세요." -ForegroundColor Red
    return
}

# 3.1 내 프로필 조회
Invoke-Api -Method GET -Path "/api/users/me" -Auth -Title "3.1 내 프로필 조회"

# 3.2 내 프로필 수정
Invoke-Api -Method PUT -Path "/api/users/me" -Auth -Title "3.2 내 프로필 수정" -Body @{
    name               = "수정된이름"
    campus             = "JUKJEON"
    college            = "SW융합대학"
    major              = "소프트웨어학과"
    grade              = "4학년"
    interestJobPrimary = "백엔드 개발자"
    tagline            = "안녕하세요, 반갑습니다."
}

# 3.3 마이페이지 조회
Invoke-Api -Method GET -Path "/api/users/mypage" -Auth -Title "3.3 마이페이지 조회"

# 3.4 비밀번호 변경 (실제 변경되므로 기본은 스킵)
Write-Host "`n[3.4 비밀번호 변경] 예시만 표기 - 실제 변경을 원하면 아래 주석을 해제하세요." -ForegroundColor Yellow
# Invoke-Api -Method POST -Path "/api/users/password/change" -Auth -Title "3.4 비밀번호 변경" -Body @{
#     currentPassword    = "Password1234"
#     newPassword        = "NewPassword1234"
#     newPasswordConfirm = "NewPassword1234"
# }
