@echo off
REM ─────────────────────────────────────────────────────────────────────────────
REM  auto-trading-mvp 모의투자 실행 스크립트
REM
REM  사용 전: .env.example 참고해서 아래 환경변수를 설정하세요.
REM
REM  방법 1 (영구 설정):
REM    setx KIS_APPKEY      "your_appkey"
REM    setx KIS_SECRETKEY   "your_secretkey"
REM    setx KIS_ACCOUNT_NO  "50000000-01"
REM    (setx 후 새 터미널 열어야 적용)
REM
REM  방법 2 (이 파일에 직접 입력 — 절대 git commit 하지 말 것):
REM    아래 SET 줄 주석 제거 후 값 입력
REM ─────────────────────────────────────────────────────────────────────────────

REM SET KIS_APPKEY=
REM SET KIS_SECRETKEY=
REM SET KIS_ACCOUNT_NO=
REM SET TELEGRAM_BOT_TOKEN=
REM SET TELEGRAM_CHAT_ID=

REM ─── 환경변수 확인 ────────────────────────────────────────────────────────────
IF "%KIS_APPKEY%"=="" (
    echo [ERROR] KIS_APPKEY 환경변수가 설정되지 않았습니다.
    echo         .env.example 을 참고해 설정 후 재실행하세요.
    pause
    exit /b 1
)
IF "%KIS_SECRETKEY%"=="" (
    echo [ERROR] KIS_SECRETKEY 환경변수가 설정되지 않았습니다.
    pause
    exit /b 1
)
IF "%KIS_ACCOUNT_NO%"=="" (
    echo [ERROR] KIS_ACCOUNT_NO 환경변수가 설정되지 않았습니다.
    pause
    exit /b 1
)

echo [INFO] 모의투자 서버 시작 중...
echo [INFO] KIS 계좌: %KIS_ACCOUNT_NO%
echo [INFO] 모의투자 API URL: https://openapivts.koreainvestment.com:29443
echo.

.\gradlew.bat bootRun --args="--spring.profiles.active=paper"
