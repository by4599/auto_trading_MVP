# 17_ops — 전고점(peakEquity) 오염 조사와 수정 절차

> 작성 2026-09-17 07:40 · 리더 직접 조사. **운영 DB는 건드리지 않았다** — 조회는 백업 사본
> (`backup/trading-db-20260916_233002.zip`)에서만 했고, 수정 SQL도 그 사본에서만 시험했다.
> **아직 아무것도 고치지 않았다.** §4 절차는 사람이 실행한다.

## 0. 한 줄

저장된 전고점 **10,890,158원**이 실측 최고 기록(**10,088,806원**)보다 7.94% 높아, 계좌가 강제정지
문턱보다 **13,182원(0.135%)** 아래에 갇혔다. **9/10 이후 매매 0건**이고, 값을 고치지 않고 재가동하면
다음 개장 직후 같은 이유로 또 멈춘다.

## 1. 확인된 사실

| 항목 | 값 | 근거 |
|---|---|---|
| 저장된 전고점 | 10,890,158원 | 백업 DB `portfolio_state.PEAK_EQUITY` |
| 원값 보존 키 | **없음** | `portfolio_state` 행 3개뿐(PEAK_EQUITY·RUN_STREAK_DAYS·RUN_STREAK_LAST_DATE) → 클램프 교정이 일어난 적 없다 = 이 값은 "정상 갱신"으로 저장됐다 |
| 실측 최고 자산 | 10,088,806원 (2026-09-01) | `daily_equity` 42행의 MAX(start_equity) |
| 현재 자산 | 9,787,960원 (전액 현금) | `/api/daily-pnl`, `/api/position` 빈 목록 |
| 현재 낙폭 | −10.121% | 9,787,960 ÷ 10,890,158 − 1 |
| 강제정지 문턱(10%) | 9,801,142원 | 전고점 × 0.9 — 현재 자산보다 13,182원 위 |
| 검증기 상한 | 11,602,127원 | 실측 최고 × (1 + 장중 허용폭 15% = 비중 10% × 5종목 × 가격제한 30%) → 10,890,158은 이 아래라 통과 |
| 최초 발동 | 2026-09-10 10:10 (MDD 10.00%, 자산 9,800,689) | 그때 실제 매도 2건 체결 — 이후 전액 현금 |
| 반복 발동 | 09-15 09:00:03 · 09-16 09:00:21 (둘 다 MDD 10.12%) | 기동할 때마다 개장 직후 재발동 |
| 매매 | **09-10 이후 0건** | 주문 로그 없음, 일별 시작 자산 9,787,960 고정(09-11~09-17) |
| 현재 상태 | EMERGENCY_STOPPED, 보유 0 | `/api/status` |

## 2. 오염 경로

- 갱신 규칙(`ShadowPortfolio.tick`, 1초 주기): ① 스냅샷이 신선하고 ② 현재 총자산 > 전고점이고
  ③ 검증기 상한 이하이면 갱신하고 **DEBUG로만 기록**한다(`ShadowPortfolio.java:130`) — 그래서 언제
  올라갔는지 INFO 로그에 남지 않는다.
- 이 값이 로그에 처음 보이는 것은 **2026-09-10 08:30 기동 시 "복원" 줄**이고, 그 이전 일자 로그
  전체에는 흔적이 없다(전 일자 gz 스캔). 따라서 09-09 세션 중에 저장된 것으로 보인다.
  그날 시작 자산은 9,881,227원 — 10,890,158원은 하루 **+10.2%**로, 최대 노출 50%인 계좌에서 불가능하다.
- 같은 부류의 오독이 **09-11 17:17에 17,047,935원**으로 관측됐고 상한을 넘어 거부됐다(WARN 3회).
  즉 **잔고 총자산 값이 가끔 튀는 계좌**이고, 상한 아래로 튄 값 하나가 전고점을 영구 오염시켰다.
- 진단 분류(PERFORMANCE-GOVERNANCE §6): **① 구현 버그 / 데이터 오염**. 전략 실패(④ 알파 소멸)가
  아니다 — 09-10 이후 신호가 실행된 적 자체가 없다.

## 3. 고칠 값

| 안 | 값 | 고친 뒤 낙폭 | 성격 |
|---|---|---|---|
| **A (권고)** | **10,088,806원** (실측 최고) | −2.98% | 증거로 뒷받침되는 가장 보수적인 값. 안전장치를 느슨하게 만들지 않는다 |
| B | 9,787,960원 (현재 자산) | 0% | 과거 고점을 지워 MDD 감시를 사실상 리셋 — 더 헐겁다 |
| C | 그대로 | −10.12% | 매매 영구 불가 |

→ **A 권고.** 검증기 주석의 원칙("전고점 하향은 안전장치를 느슨하게 하는 방향")과 같은 결이다.

## 4. 수정 절차 (사람이 실행 · 09:00 이전 또는 15:30 이후)

> 장 시작 전에 끝내면 그날 연속 무중단 기록도 유지된다(개장 전 기동이면 인정).

**0) 백업** — 23:30 자동 백업이 있지만 직전 상태로 한 번 더 뜬다.
```powershell
cd C:\Users\SAMSUNG\Desktop\workspace\auto_trading
.\backup-to-supabase.ps1 -SkipUpload
```

**1) 앱 정지** — 앱은 08:30 예약 작업이 붙잡고 있다.
```powershell
Stop-ScheduledTask -TaskName AutoTrading-Paper-0830
Start-Sleep -Seconds 5
Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue   # 결과 없음 = 정지 완료
```

**2) 전고점 값 수정** — 앱이 꺼진 상태에서만. (백업 사본에서 시험 완료: Update count 1, 값 확인)
```powershell
$h2 = 'C:\Users\SAMSUNG\.gradle\caches\modules-2\files-2.1\com.h2database\h2\2.2.224\7bdade27d8cd197d9b5ce9dc251f41d2edc5f7ad\h2-2.2.224.jar'
$db = 'C:\Users\SAMSUNG\Desktop\workspace\auto_trading\trading-db'
java -cp $h2 org.h2.tools.Shell -url "jdbc:h2:file:$db" -user sa -password "" -sql "UPDATE portfolio_state SET state_value = 10088806 WHERE state_key = 'PEAK_EQUITY'; SELECT * FROM portfolio_state"
```

**3) 앱 기동**
```powershell
Start-ScheduledTask -TaskName AutoTrading-Paper-0830
```

**4) 값 확인** — 기동 로그에 `peakEquity 복원: 1.0088806E7`이 찍혀야 한다.
`오염된 peakEquity 교정` 줄이 나오면 멈추고 다시 본다(값이 상한을 넘었다는 뜻).
```powershell
Select-String -Path .\logs\paper.log -Pattern 'peakEquity' | Select-Object -Last 3
```

**5) 재가동 게이트** — 원인 진단을 reason에 남겨야 통과한다.
```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/trading/resume -ContentType 'application/json' -Body '{"confirm":"CONFIRM_RESUME","reason":"peak equity pollution 10890158 -> 10088806 (evidence-based max), governance section 6 case 1 data corruption"}'
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/trading/start
Invoke-RestMethod -Uri http://localhost:8080/api/status
```
→ `tradingMode`가 RUNNING이면 완료.

**6) 개장 후 확인 (09:05쯤)** — RUNNING 유지, MDD 강제청산 로그 없음, 신호가 나면 주문이 나가는지.

## 5. 되돌리기

- 값만 되돌리기: §4-2의 SQL에서 `10088806` 대신 `10890158`을 쓴다.
- DB 전체 되돌리기: `docs/OPERATIONS.md` §9.2 복구 절차(백업 zip 교체).

## 6. 고친 뒤에도 남는 것 (별건 · 백로그 후보)

- **근본 원인은 잔고 오독**이다. 상한 아래로 튄 값은 앞으로도 통과한다. 후보:
  ① 전고점 갱신 로그를 DEBUG→INFO로 올려 "언제·얼마로 올랐는지" 남기기
  ② 갱신 시 텔레그램 알림(고점 경신은 드문 사건)
  ③ KIS 잔고 응답 자체의 오독 원인 조사(17,047,935원 사례)
- 전고점을 고치는 기능이 앱에 없다 — DB 직접 수정이 유일한 길이다. 엔드포인트 추가는 별건 판단.
- 연속 무중단 가동일은 "떠 있었는지"만 세므로 멈춘 채 떠 있던 09-15·09-16도 카운트됐다.
  릴리즈 항목의 의미(매매가 도는 상태로 5일)를 다시 정의할지는 사용자 판단.
