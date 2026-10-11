# 3_verify_manual-buy-drill — 증거 기반 검증

검증 일시: 2026-08-12 21:45~22:05 KST
대상: `POST /api/trading/manual-buy-drill` 구현 + `run-manual-buy-then-drill.ps1` + 작업 스케줄러 등록
검증자: paper-ops-verifier (spring-build-verify / paper-ops-check 절차)

**한 줄 결론: 코드는 통과. 그런데 내일 아침 자동 실행은 지금 상태로는 실패한다 —
PowerShell 스크립트 인코딩 결함 1건(치명) + 앱이 꺼져 있음.**

---

## 1. 증거표

| # | 주장 | 검증 명령 | 결과 | 판정 |
|---|---|---|---|---|
| 1 | 테스트 515개 통과 | `gradle test --console=plain` (직접 재실행) | tests=515 failures=0 errors=0 skipped=0, 종료 코드 0 | ✅ |
| 2 | 스프링 컨텍스트 기동 성공(의존성 4개 추가) | `gradle bootRun --args="--spring.profiles.active=paper"` 실제 기동 | `Started TradingApplication in 8.048 seconds`, BeanCreationException/UnsatisfiedDependency/Circular **0건** | ✅ |
| 3 | 신규 엔드포인트 실제 라우팅 | 살아있는 앱에 잘못된 confirm으로 POST | HTTP 200 · `{"success":false,"message":"확인 문자열 불일치 …CONFIRM_MANUAL_BUY…"}` | ✅ |
| 4 | 스크립트 문법 정상 | `[Parser]::ParseFile()` (파싱 전용, 미실행) | **구문 오류 7건** | ❌ **치명** |
| 5 | 스케줄러 등록 | `schtasks /query /tn ... /v /fo LIST` | 다음 실행 2026-08-13 09:02:00, 준비 상태 | ✅(제약 있음) |
| 6 | 앱 가동 중 | `curl localhost:8080/api/status` (검증 시작 시점) | 연결 거부(exit 7), java 프로세스 0개 | ❌ **꺼져 있음** |
| 7 | 연속 가동일 5일 | `GET /api/trading/run-streak` | `streakDays:7, goalDays:5, achieved:true` | ✅ **관문 통과** |
| 8 | 청산 리허설 1회 성공 | 실행 기록 조회 | 없음 | ❌ 미실행 |
| 9 | 연속손실 라운드트립 집계 | `TradeResultTracker` 소스 확인 | 17행 주석 "매도 체결 청크 단위로 1회 기록" — 미전환 | ❌ 잔존 |

---

## 2. ❌ 치명 결함 — 내일 09:02 자동 실행은 실패한다 (인코딩)

### 증거

```
파일: run-manual-buy-then-drill.ps1
인코딩: UTF-8, BOM 없음 (첫 바이트 23 20 ea b0 95 — BOM 아님)
실행 환경: Windows PowerShell 5.1.26100.9168, ANSI 코드페이지 = 949 (한국어)

[Parser]::ParseFile(파일경로)          → 구문 오류 7건
[Parser]::ParseInput(UTF8로 직접 읽음)  → 구문 오류 0건
```

오류 목록(ParseFile):
```
line 76 : 배열 인덱스 식이 없거나 잘못되었습니다
line 82 : 배열 인덱스 식이 없거나 잘못되었습니다
line 82 : 문자열에 " 종결자가 없습니다
line 85 : 뒤에 닫는 ')'가 없습니다
line 69 : 문 블록 또는 형식 정의에 닫는 '}'가 없습니다
line 38 : 문 블록 또는 형식 정의에 닫는 '}'가 없습니다
line 85 : Try 문에 해당 Catch 문 또는 Finally 블록이 없습니다
```

### 원인

BOM 없는 UTF-8 파일을 Windows PowerShell 5.1은 **시스템 ANSI 코드페이지(949)로 읽는다.**
한글 주석·문자열의 UTF-8 바이트가 CP949로 잘못 해독되면서, 2바이트 문자로 오인된
선행 바이트가 뒤따르는 ASCII 문자(`"` 등)를 삼켜 문법이 깨진다.

76행을 CP949로 해독한 실제 모습:
```
Write-Log "泥��궛 �쓳�떟: $($drillResp | ConvertTo-Json -Compress)"
```

### 영향

스케줄러에 등록된 실행 명령이 정확히 이 경로를 탄다:
```
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "...\run-manual-buy-then-drill.ps1"
```
→ 내일 09:02에 **스크립트가 첫 줄도 실행되지 않고 문법 오류로 죽는다.**
스크립트 안의 try/catch·텔레그램 알림은 **파싱 단계에서 죽으므로 작동하지 않는다.**
즉 사용자는 실패 사실조차 통보받지 못한다(조용한 실패).

### 조치 (구현자/리더가 해야 함)

파일을 **UTF-8 with BOM**으로 다시 저장한다. 예:
```powershell
$c = [System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8)
[System.IO.File]::WriteAllText($p, $c, [System.Text.UTF8Encoding]::new($true))
```
저장 후 **반드시 `[Parser]::ParseFile()`로 오류 0건을 재확인**한다.
(대안: 주석·문자열을 전부 ASCII로 바꾼다.)

> ⚠ 검증자는 이 파일을 수정하지 않았다. 실행도 하지 않았다(주문 유출 방지).

---

## 3. ✅ 스크립트 로직·계약 검증 (인코딩 외에는 정상)

컨트롤러 원본과 1:1 대조한 결과 **불일치 0건**:

| 스크립트가 부르는 것 | 실제 코드 | 일치 |
|---|---|---|
| `GET /api/status` → `$status.tradingMode` | `SettingsController:50` → `result.put("tradingMode", …)` | ✅ |
| `POST /api/trading/manual-buy-drill` body `{"confirm":"CONFIRM_MANUAL_BUY"}` | `TradingController:178`, 상수 45행 `= "CONFIRM_MANUAL_BUY"` | ✅ |
| 응답 `.success` / `.message` | `result()` 238~243행이 `success`/`message`/`tradingMode` 반환 | ✅ |
| `GET /api/position` → `.stockCode` / `.quantity` | `DashboardController:85` → `item.put("stockCode"…)`, `item.put("quantity"…)` | ✅ |
| `POST /api/trading/liquidation-drill` body `{"confirm":"CONFIRM_LIQUIDATE"}` | `TradingController:156~158` | ✅ |
| 매수 수량 10주 / 005930 | 상수 47~48행 `"005930"`, `10` | ✅ |
| 환경변수 `TELEGRAM_*` User 스코프 조회 | User 스코프에 5개 모두 SET 확인 | ✅ |

감사 MEDIUM #1(응답만 믿지 않기)은 4단계 포지션 재확인으로, MEDIUM #2(500 예외 전파)는
바깥 try/catch로 각각 흡수된다 — 로직상 처리됨을 확인했다.

---

## 4. 운영 점검 (paper-ops-check)

```
[운영 점검] 2026-08-12 21:45 KST (검증 시작 시점)
| 항목 | 상태 | 근거 |
|---|---|---|
| 앱 | ❌ 꺼져 있음 | curl exit 7, 8080 리슨 없음, java 프로세스 0개 |
| 운전 상태 | 점검 불가(앱 꺼짐) → 검증용 기동 후 RUNNING | /api/status |
| 연속 가동일 | 7일 (목표 5일) **달성** | streakDays:7, lastRecordedDate 2026-08-11 |
| 보유 포지션 | 012330 1주 @503,000 (손절 453,179) | /api/position |
| 미체결 잔존 | 012330 BUY 1주 ACCEPTED (12:58 접수, 취소 거부: "모의투자 장종료") | DB ORDER_HISTORY 조회 |
| 당일 손익 | 실현 0, 평가 -4,500원 | /api/pnl/daily |
| 연속손실 | 0회 | /api/risk/status |
| 청산 리허설 | 미실행 | 기록 없음 |
```

- 앱은 오늘 **12:58경 비정상 종료**된 것으로 보인다(H2 락 파일 12:48, DB 최종 수정 12:58,
  미체결 주문 접수 시각 12:58:09).
- 검증용 기동 시 기동 재동기화가 정상 작동했다:
  `[Reconciler] 브로커 기준 보정 1건: [012330: DB 없음 → 브로커 1주@503000 (신규 생성)]`
  + `[StopLoss] 손절선 장착: 012330 … 453179` → **검증자가 앱을 켠 부수효과로 DB가
  브로커 기준으로 교정되고 손절선이 장착됐다**(원래 다음 기동 때 일어날 일, 무해·유익).
- 검증 후 **앱은 다시 종료했다**(원래 상태 복원). 포트 8080 해제 확인.

### 내일 10주 매수가 리스크 룰에 걸릴까 — 코드 대조 결과 "걸리지 않는다"

| 룰 | 판정 근거 |
|---|---|
| `PendingOrderRule` | **종목 단위** 검사(42·51행). 잔존 미체결은 012330이라 005930은 통과 |
| `PositionLimitRule` | 현재 005930 비중 0% < 10% → 통과 (사후 비중은 검사하지 않음) |
| `MaxPositionCountRule` | 보유 1종목 < 5 → 통과 |
| `MarketCloseRule` | 09:02 < 15:20 → 통과 |
| `DailyLossRule` | 당일 -0.0005% → 통과 |
| `ConsecutiveLossRule` | 연속손실 0회 → 통과 |
| `BucketBudgetRule` | VB칸 활성(`isBucketActive: VB → true`), 가용현금 ≈ 950만원 > 0 → 통과. 10주 × 255,500 = 약 256만원 |

> 005930 종가 255,500원 기준 10주 ≈ **2,555,000원**.

---

## 5. 작업 스케줄러 등록 확인 + 제약

```
작업 이름 : \AutoTrading-ManualBuyDrill-Once
다음 실행 : 2026-08-13 오전 9:02:00     상태: 준비
로그온 모드: 대화형만                    실행 사용자: DESKTOP-ROSJO8G\SAMSUNG
실행할 작업: powershell.exe -NoProfile -ExecutionPolicy Bypass -File "...\run-manual-buy-then-drill.ps1"
전원 관리 : 배터리로 전환되는 경우 중지, 배터리로 전환되는 경우 시작 안 함
절전 해제 : (설정 정보 없음 — PC를 깨우지 않음)
다시 시작하면 예약된 작업 실행: 사용 안 함
```

**사람이 알아야 할 제약 3가지**
1. **"대화형만"** — 사용자 SAMSUNG이 **로그온된 상태**여야 실행된다. 로그오프/다른 사용자 전환 시 안 돈다.
2. **절전/최대 절전 시 PC를 깨우지 않는다** — 09:02에 자고 있으면 그냥 건너뛴다.
3. **배터리 전원이면 시작하지 않는다** — 노트북이라면 반드시 어댑터를 꽂아 둔다.

---

## 6. 릴리즈 갭 3종 현황

| 관문 | 상태 | 근거 |
|---|---|---|
| 모의계좌 강제청산 리허설 1회 | ❌ 미실행 | 내일 09:02 시나리오 대기 (Gate 2 판정 보류) |
| 모의투자 연속 5거래일 무중단 | ✅ **달성 (7일)** | `run-streak` streakDays:7 / goalDays:5 / achieved:true |
| 연속손실 라운드트립 전환 | ❌ 잔존 | `TradeResultTracker` 17행 "매도 체결 청크 단위" 주석 그대로 |

---

## 7. 최종 판정

| 구분 | 판정 |
|---|---|
| 구현(코드) | ✅ 통과 — 515/515, 종료 코드 0, 실제 컨텍스트 기동 성공, 엔드포인트 라우팅 확인 |
| 자동 실행 준비 | ❌ **미완료 2건** — ① 스크립트 인코딩(치명, 조용한 실패) ② 앱 꺼져 있음 |

**→ 미완료 2건 — 완료 아님.** 인코딩 수정 + 재파싱 확인 + 앱 기동 없이는
내일 09:02 시나리오는 실행되지 않는다.
