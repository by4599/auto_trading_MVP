# 청산 리허설(Gate 2) 타이밍 감시기.
# 작업 스케줄러(AutoTrading-Drill-Watch)가 10분마다 실행한다.
# 장중(평일 09:00~15:10 KST)에 앱이 RUNNING이고 보유 종목이 1개 이상이면
# "지금 리허설 돌리기 좋다"고 텔레그램으로 한 번 알린다(하루 1회, 날짜 마커로 중복 방지).
# 장 마감/보유 0/이미 알림/멈춤 상태면 조용히 종료한다.

$ErrorActionPreference = 'Stop'

# ── 1. KST 시각·거래시간 가드 ────────────────────────────────────────────────
$kst = [System.TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::UtcNow, 'Korea Standard Time')
if ($kst.DayOfWeek -eq 'Saturday' -or $kst.DayOfWeek -eq 'Sunday') { exit 0 }
$minutes = $kst.Hour * 60 + $kst.Minute
if ($minutes -lt (9 * 60) -or $minutes -ge (15 * 60 + 10)) { exit 0 }   # 09:00 이전 / 15:10 이후 제외

# ── 2. 하루 1회 중복 알림 방지 마커 ──────────────────────────────────────────
$marker = Join-Path $env:TEMP ("drill-watch-alerted-" + $kst.ToString('yyyyMMdd') + ".flag")
if (Test-Path $marker) { exit 0 }

# ── 3. 앱 상태·보유 포지션 조회 ──────────────────────────────────────────────
try {
    $status = Invoke-RestMethod -Uri 'http://localhost:8080/api/status' -TimeoutSec 6
} catch { exit 0 }   # 앱이 안 떠 있으면 조용히 종료
if ($status.tradingMode -ne 'RUNNING') { exit 0 }

try {
    $positions = Invoke-RestMethod -Uri 'http://localhost:8080/api/position' -TimeoutSec 8
} catch { exit 0 }
if (-not $positions -or $positions.Count -lt 1) { exit 0 }   # 보유 없으면 종료

# ── 4. 텔레그램 알림 (앱과 동일한 봇/채팅) ───────────────────────────────────
$token = [Environment]::GetEnvironmentVariable('TELEGRAM_BOT_TOKEN', 'User')
$chat  = [Environment]::GetEnvironmentVariable('TELEGRAM_CHAT_ID', 'User')
if ([string]::IsNullOrWhiteSpace($token)) { $token = $env:TELEGRAM_BOT_TOKEN }
if ([string]::IsNullOrWhiteSpace($chat))  { $chat  = $env:TELEGRAM_CHAT_ID }
if ([string]::IsNullOrWhiteSpace($token) -or [string]::IsNullOrWhiteSpace($chat)) { exit 1 }

$codes = ($positions | ForEach-Object { $_.stockCode }) -join ', '
$msg = @"
[청산 리허설 타이밍] $($kst.ToString('MM-dd HH:mm')) KST
지금 앱이 $($positions.Count)종목 보유 중 ($codes) — 청산 리허설(Gate 2) 돌리기 좋은 타이밍입니다.
장중(15:10 전)에 실행하세요. 실행 후 Claude가 텔레그램·로그로 매도 체결을 함께 확인합니다.

리허설 시작:
curl -X POST http://localhost:8080/api/trading/liquidation-drill -H "Content-Type: application/json" -d "{\"confirm\":\"CONFIRM_LIQUIDATE\"}"
"@

$json  = @{ chat_id = $chat; text = $msg } | ConvertTo-Json -Compress
$bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
Invoke-RestMethod -Uri "https://api.telegram.org/bot$token/sendMessage" `
    -Method Post -ContentType 'application/json' -Body $bytes -TimeoutSec 10 | Out-Null

# 성공 시에만 마커 기록 → 오늘은 더 안 보냄
New-Item -ItemType File -Path $marker -Force | Out-Null
