# 04_event.ps1 - Event (활동) API 테스트  /api/events
# 사용법: powershell -ExecutionPolicy Bypass -File .\04_event.ps1
# 참고: /recommended 는 인증 필요. 나머지는 비인증 접근 가능(인증 시 개인화 정렬).
. "$PSScriptRoot\_common.ps1"

Write-Host "`n########## 4. Event (활동) - /api/events ##########" -ForegroundColor Magenta

$hasToken = [bool](Get-AccessToken)

# 4.1 활동 검색 - 필터 없음 (비인증)
Invoke-Api -Method GET -Path "/api/events/search" -Title "4.1 활동 검색 (필터 없음)"

# 4.1 활동 검색 - category 필터
Invoke-Api -Method GET -Path "/api/events/search?category=CONTEST" -Title "4.1 활동 검색 (category=CONTEST)"

# 4.1 활동 검색 - college + category 필터
Invoke-Api -Method GET -Path "/api/events/search?college=SW%EC%9C%B5%ED%95%A9%EB%8C%80%ED%95%99&category=SCHOOL" -Title "4.1 활동 검색 (college + category)"

# 4.1 활동 검색 - 인증 사용자 개인화 정렬
if ($hasToken) {
    Invoke-Api -Method GET -Path "/api/events/search" -Auth -Title "4.1 활동 검색 (인증 - 개인화 정렬)"
}

# 4.2 맞춤 활동 추천 [인증 필수]
if ($hasToken) {
    Invoke-Api -Method GET -Path "/api/events/recommended" -Auth -Title "4.2 맞춤 활동 추천 (전체 카테고리)"
    Invoke-Api -Method GET -Path "/api/events/recommended?category=EXTERNAL" -Auth -Title "4.2 맞춤 활동 추천 (category=EXTERNAL)"
} else {
    Write-Host "`n[4.2 맞춤 활동 추천] 스킵 - 인증 필요. 먼저 .\02_auth.ps1 로그인." -ForegroundColor Yellow
    # 인증 없이 호출하면 401/UNAUTHORIZED 를 반환하는지 확인
    Invoke-Api -Method GET -Path "/api/events/recommended" -Title "4.2 맞춤 활동 추천 (비인증 - 실패 확인)"
}

# 4.3 전체 활동 조회 (랜덤)
Invoke-Api -Method GET -Path "/api/events" -Title "4.3 전체 활동 조회 (랜덤)"
