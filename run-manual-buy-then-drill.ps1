# 강제청산 리허설(Gate 2) 시나리오 — 1회성 예약 실행.
# Windows 작업 스케줄러(AutoTrading-ManualBuyDrill-Once)가 2026-08-13 09:0x KST에 1회 실행한다.
#
# 순서: 앱 RUNNING 확인 -> 삼성전자(005930) 10주 강제 매수(manual-buy-drill)
#       -> 10분 대기 -> 실제 포지션 보유 재확인(응답 JSON만 믿지 않음, risk-audit 지적 반영)
#       -> 강제청산 리허설(liquidation-drill) -> 결과를 로그 파일 + 텔레그램으로 통지.
#
# 사용자 승인 근거: 2026-08-12 라이브 세션, "삼성전자 10주 강제 매수 후 10분 뒤 청산 시나리오"
# 무인 실행 명시 승인. 실전(real) 계좌와는 무관 — 모의(paper) 전용 엔드포인트만 호출한다.

$ErrorActionPreference = 'Stop'
$logFile = Join-Path $env:TEMP ("liquidation-drill-scenario-" + (Get-Date -Format 'yyyyMMdd_HHmmss') + ".log")

function Write-Log($msg) {
    $line = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $msg"
    Add-Content -Path $logFile -Value $line
    Write-Output $line
}

$token = [Environment]::GetEnvironmentVariable('TELEGRAM_BOT_TOKEN', 'User')
$chat  = [Environment]::GetEnvironmentVariable('TELEGRAM_CHAT_ID', 'User')
if ([string]::IsNullOrWhiteSpace($token)) { $token = $env:TELEGRAM_BOT_TOKEN }
if ([string]::IsNullOrWhiteSpace($chat))  { $chat  = $env:TELEGRAM_CHAT_ID }

function Send-Notify($msg) {
    Write-Log "[알림] $msg"
    if ([string]::IsNullOrWhiteSpace($token) -or [string]::IsNullOrWhiteSpace($chat)) { return }
    try {
        $json  = @{ chat_id = $chat; text = $msg } | ConvertTo-Json -Compress
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
        Invoke-RestMethod -Uri "https://api.telegram.org/bot$token/sendMessage" `
            -Method Post -ContentType 'application/json' -Body $bytes -TimeoutSec 10 | Out-Null
    } catch {
        Write-Log "텔레그램 전송 실패(무시하고 계속): $($_.Exception.Message)"
    }
}

try {
    Write-Log "=== 시나리오 시작 ==="

    # 1. 앱 상태 확인 — RUNNING 아니면 즉시 중단(강제 진행 금지)
    $status = Invoke-RestMethod -Uri 'http://localhost:8080/api/status' -TimeoutSec 10
    Write-Log "앱 상태: tradingMode=$($status.tradingMode)"
    if ($status.tradingMode -ne 'RUNNING') {
        Send-Notify "[리허설 시나리오] 중단 — 앱 상태가 RUNNING이 아닙니다 ($($status.tradingMode)). 사람이 확인 후 재시도 필요."
        exit 1
    }

    # 2. 삼성전자 10주 강제 매수
    $buyResp = Invoke-RestMethod -Uri 'http://localhost:8080/api/trading/manual-buy-drill' `
        -Method Post -ContentType 'application/json' -Body '{"confirm":"CONFIRM_MANUAL_BUY"}' -TimeoutSec 15
    Write-Log "매수 응답: $($buyResp | ConvertTo-Json -Compress)"
    if (-not $buyResp.success) {
        Send-Notify "[리허설 시나리오] 매수 거부됨 — $($buyResp.message)"
        exit 1
    }
    Send-Notify "[리허설 시나리오] 1/3 매수 접수 — $($buyResp.message). 10분 대기 후 청산 리허설을 진행합니다."

    # 3. 10분 대기 (체결 파이프라인이 비동기이므로 응답=success여도 실제 체결은 별도 확인 필요)
    Start-Sleep -Seconds 600

    # 4. 실제 포지션 보유 재확인 — 응답 JSON만 믿지 않는다(risk-audit MEDIUM #1 반영)
    $positions = $null
    try { $positions = Invoke-RestMethod -Uri 'http://localhost:8080/api/position' -TimeoutSec 10 } catch { }
    $held = $positions | Where-Object { $_.stockCode -eq '005930' }
    if (-not $held -or [int]$held.quantity -lt 1) {
        Write-Log "경고 — 10분 뒤에도 005930 보유가 확인되지 않음"
        Send-Notify "[리허설 시나리오] 경고 — 10분 뒤에도 삼성전자 보유가 확인되지 않았습니다(매수 미체결 가능). 그래도 청산 리허설은 시도합니다 — 사람이 로그로 재확인 필요."
    } else {
        Write-Log "포지션 확인됨 — 005930 $($held.quantity)주"
    }

    # 5. 강제청산 리허설
    $drillResp = Invoke-RestMethod -Uri 'http://localhost:8080/api/trading/liquidation-drill' `
        -Method Post -ContentType 'application/json' -Body '{"confirm":"CONFIRM_LIQUIDATE"}' -TimeoutSec 15
    Write-Log "청산 응답: $($drillResp | ConvertTo-Json -Compress)"
    Send-Notify "[리허설 시나리오] 2-3/3 청산 리허설 개시 — $($drillResp.message). 최종 체결 여부는 텔레그램 청산 알림과 대시보드로 사람이 확인해주세요. 앱은 이후 EMERGENCY_STOPPED로 남습니다(재가동은 /api/trading/resume, 사람 확인 후)."

    Write-Log "=== 시나리오 종료 (요청까지는 정상) ==="
}
catch {
    Write-Log "예외 발생: $($_.Exception.Message)"
    Send-Notify "[리허설 시나리오] 오류로 중단 — $($_.Exception.Message). 로그: $logFile"
    exit 1
}
