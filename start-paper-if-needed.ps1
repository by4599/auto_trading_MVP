# 평일 08:30 작업 스케줄러(AutoTrading-Paper-0830)가 실행하는 모의투자 앱 기동 래퍼.
# 이미 실행 중이면(8080 리슨) 중복 기동하지 않는다 — 포트 충돌로 죽는 gradle
# 찌꺼기 프로세스가 남는 것을 막는다 (2026-07-20 프로세스 잔재 사고 재발 방지).
$listening = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($listening) {
    Write-Output "[start-paper] 이미 실행 중 (8080 리슨) - 기동 생략"
    exit 0
}
Set-Location $PSScriptRoot
Write-Output "[start-paper] 모의투자 앱 기동: $PSScriptRoot"
cmd /c run-paper.bat
