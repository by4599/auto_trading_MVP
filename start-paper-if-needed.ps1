# 모의투자 앱 자동 기동 래퍼 — 작업 스케줄러가 실행한다.
#   AutoTrading-Paper-0830  : 평일 08:30        (-Reason 0830)
#   AutoTrading-Paper-Logon : 로그온 2분 뒤      (-Reason logon) — 장중에 PC가 재부팅돼도 앱을 다시 켠다
#
# 주말이거나, 이미 실행 중(8080 리슨)이거나, 켜지는 중(paper 프로필 java 프로세스)이면
# 기동하지 않는다. 포트는 기동 1~2분 뒤에야 열리므로 포트만 보면 켜지는 중인 앱을 놓쳐
# gradle이 두 번 뜨고 포트 충돌 찌꺼기 프로세스가 남는다 (2026-07-20 프로세스 잔재 사고).
#
# 앱이 떠 있는 동안 이 스크립트(작업)도 살아 있다 — 창을 닫으면 앱도 꺼진다.
# 그래서 두 작업 모두 "배터리로 전환하면 중지"를 꺼 둔다 (노트북 전원선이 빠져도 유지).
#
# 기동기를 .bat로 두지 않는 이유: 이 PC의 은행·증권 보안 모듈이 새로 만든 .bat/.cmd 파일을
# 열지 못하게 붙잡는다 (2026-09-16 실측 — 새 .txt/.ps1은 즉시 열리고 .bat/.cmd만 멈춤).
#
# 기록: logs\auto-start.log — 기동·생략·종료 시각. 종료 줄 없이 끊겼으면 PC가 꺼진 것이다.
param(
    [string]$Reason = 'manual',
    [switch]$DryRun
)

$log = Join-Path $PSScriptRoot 'logs\auto-start.log'
New-Item -ItemType Directory -Force -Path (Split-Path $log) | Out-Null

function Write-StartLog([string]$message) {
    $line = '{0} [{1}] {2}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Reason, $message
    Add-Content -Path $log -Value $line -Encoding UTF8
    Write-Output "[start-paper] $message"
}

$day = (Get-Date).DayOfWeek
if ($day -eq 'Saturday' -or $day -eq 'Sunday') {
    Write-StartLog '주말 - 기동 생략'
    exit 0
}
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
    Write-StartLog '이미 실행 중(8080 리슨) - 기동 생략'
    exit 0
}
$starting = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -match 'spring\.profiles\.active=paper' }
if ($starting) {
    Write-StartLog '켜지는 중인 앱 프로세스 있음 - 기동 생략'
    exit 0
}
if ($DryRun) {
    Write-StartLog '기동 필요 - DryRun이라 켜지 않음'
    exit 0
}

Set-Location $PSScriptRoot
Write-StartLog '앱 기동 시작'
cmd /c run-paper.bat
Write-StartLog "앱 종료 (코드 $LASTEXITCODE)"
