# 01_health.ps1 - Health (헬스체크) API 테스트
# 사용법: powershell -ExecutionPolicy Bypass -File .\01_health.ps1
. "$PSScriptRoot\00_common.ps1"

Write-Host "`n########## 1. Health (헬스체크) ##########" -ForegroundColor Magenta

# 1.1 루트 상태 확인
Invoke-Api -Method GET -Path "/" -Title "1.1 루트 상태 확인"

# 1.2 헬스 체크
Invoke-Api -Method GET -Path "/health" -Title "1.2 헬스 체크"
