# 18_ops — 전고점 오염 수정 실행 기록 (17_ops §4 집행)

> 실행 2026-09-21 22:04~22:10 KST · 리더 직접 실행 (사용자 승인).
> 대상은 **운영 DB** `trading-db`. 17_ops는 백업 사본에서 시험만 했고, 이번이 실제 적용이다.

## 0. 한 줄

저장된 전고점을 **10,890,158원 → 10,088,806원**(실측 최고, A안)으로 고쳤다.
낙폭 **−10.121% → −2.982%**. 앱은 `RUNNING`으로 복귀했고 9/10 이후 계속되던
"개장 직후 강제청산 재발동" 조건이 사라졌다.

## 1. 실행 순서와 증거

| 단계 | 명령/동작 | 실측 결과 |
|---|---|---|
| 0 백업 | `backup-to-supabase.ps1 -SkipUpload` | `backup/trading-db-20260921_220444.zip` (18.2 MB) · `backtest-db-…zip` (4.0 MB). H2 `BACKUP TO` 온라인 스냅샷 |
| 1 정지 | `Stop-ScheduledTask AutoTrading-Paper-0830` | 작업은 Ready로 갔으나 **java PID 5512는 살아남았다** (작업이 손자 프로세스를 죽이지 않는다) |
| 1b 정지 | 두 작업 `Disable-ScheduledTask` → `Stop-Process -Id 5512 -Force` | `terminated` · 8080 `free` · 잔여 paper java 0개. 정지 전 상태: mode=EMERGENCY_STOPPED, 보유 0, 9/10 이후 주문 없음 → 인플라이트 주문 없음 확인 |
| 2 검산 | 운영 DB `daily_equity` 직접 조회 | 47행 (2026-07-16 ~ 2026-09-21) · `MAX(start_equity)=10088806` · `MAX(end_equity)=9787960`. **17_ops가 백업 사본에서 낸 값과 일치** |
| 3 수정 | `UPDATE portfolio_state SET state_value=10088806 WHERE state_key='PEAK_EQUITY'` | `Update count: 1` |
| 3b 확인 | `SELECT state_key, CAST(state_value AS BIGINT)` | `PEAK_EQUITY=10088806` · `RUN_STREAK_DAYS=5` · `RUN_STREAK_LAST_DATE=20260921` |
| 4 기동 | 두 작업 `Enable` → `Start-ScheduledTask AutoTrading-Paper-0830` | 8080 LISTENING, PID 31624 |
| 5 로그 | `grep peakEquity logs/paper.log` | `22:09:27.564 [ShadowPortfolio] peakEquity 복원: 1.0088806E7` — **`오염된 peakEquity 교정` 줄 없음**(= 상한 클램프가 걸리지 않았다) |
| 5b 로그 | 기동 시퀀스 | `22:09:29 [Reconciler] 브로커 대조 완료 — 불일치 없음` → `기동 재동기화 완료 — 자동 가동(RUNNING)` |
| 6 상태 | `/api/status`·`/api/risk/status`·`/api/position` | `tradingMode: RUNNING` · `dailyPnlPercent 0.0` · `consecutiveLossCount 0` · 보유 `[]` |

재가동 게이트(`/api/trading/resume`)는 **부르지 않았다** — 기동 재동기화가 정상 종료해
OPERATIONS §1의 2026-07-16 정책대로 자동 RUNNING이 됐기 때문이다(비정상 종료가 아니었다).

## 2. 수정 후 여유

| 항목 | 값 |
|---|---|
| 전고점 | 10,088,806원 |
| 현재 자산 | 9,787,960원 (전액 현금) |
| 현재 낙폭 | **−2.982%** (한도 10%) |
| 강제정지 문턱 | 9,079,925원 |
| 문턱까지 여유 | **708,035원 (7.02%p)** |

수정 전 여유는 −13,182원(문턱 아래)이었다.

## 3. 아직 확인 안 된 것

- **다음 개장(2026-09-22 화) 09:00~09:05에 RUNNING이 유지되는지**가 최종 확인이다.
  `RiskMonitor`는 장중에만 판정하므로 오늘 밤의 RUNNING은 절반의 증거다.
- 근본 원인(잔고 총자산 값이 가끔 튀는 것)은 **그대로 남아 있다.** 상한 아래로 튄 값은
  앞으로도 전고점을 오염시킬 수 있다 → Step 0-b(갱신 로그 INFO 승격 + 텔레그램 알림)로 대응.

## 4. 되돌리기

- 값만: 같은 `UPDATE`에서 `10890158`
- 전체: `backup/trading-db-20260921_220444.zip` (OPERATIONS §9.2)
