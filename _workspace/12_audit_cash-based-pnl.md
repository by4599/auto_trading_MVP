# 12_audit — 현금(예수금) 기반 손익 측정 + 체결가 0 가드

감사자: `risk-auditor` (2026-09-15) · 코드 미수정
※ 감사관 도구에 Write가 없어 리더가 보고 내용을 옮겨 적고, 후속 조치 결과를 덧붙였다.

## 판정: CRITICAL 0 · HIGH 1 · MEDIUM 3 · LOW 3

**HIGH 1건은 이번 변경이 만든 것이 아니라 기존 결함이며, 이번 변경이 악화시키지도 않았다(증분 0).**

이전 감사 이월: `5_audit` HIGH(MDD 초과 시 매도 거부)는 커밋 `0422c1f`로 ✅ 해소.
`9_audit` CRITICAL 0 · HIGH 0 — 이월 없음.

---

## §1. 최우선 질문 — 체결가 0 가드가 `ConsecutiveLossRule`을 덜 보수적으로 만드는가?

**결론: 이론적으로는 만든다. 그러나 모의에서 그 룰은 이미 100% 무력화돼 있었고, 이번 변경은
그 사실을 바꾸지 않는다.**

### (a) 진짜 손실이 누락되는 조건
`FillProcessor.index()`(`:254-265`)가 `tot_ccld_qty > 0`인데 `avg_prvs`가 공백인 행을 받으면
`FillResult.filled(qty, 0.0)` → `applyFill(id, qty, 0.0)` → `applySellFill`의 `if (fillPrice > 0)`
(`FillStateUpdater.java:324`) 실패 → `accrueRealized` 생략 + 마커 추가(`:335`) → 전량 청산 시
`recordRoundTrip` 생략(`:344-346`). **이때 그 매매가 진짜 손실이면 카운터가 안 오른다.**

### (b) 그런데 모의에서는 이 경로 자체가 안 열린다
결함 5(VTTC8001R 빈 목록)에서는 `index()`가 빈 맵 → `inquireFill`이 `notFilled()`(qty 0) →
`FillProcessor.java:109`의 `result.totalFilledQty() > snapshot.filledQuantity()`가 **거짓** →
**`applyFill`이 아예 호출되지 않는다.** 매도는 10분 타임아웃 → `cancelAll` → `NO_OPEN_QTY`(`:134`)
→ `reconcileFilledFromBalance`(`:258-288`)로 끝나는데, **이 메서드는 `TradeResult`도
`recordRoundTrip`도 남기지 않는다.**

### (c) 실측 (`logs/paper-2026-08-29 ~ 09-12` + `paper.log`, `[Test worker]` 제외)

| 항목 | 건수 |
|---|---|
| 매도 주문 접수 | **70** |
| `전량 체결: side=SELL` (= `applySellFill` 도달) | **0** (전량 체결 48건 전부 BUY) |
| `[TradeResult]` 실현손익 확정 | **0** |
| `[체결 대사] …브로커보유=0주` (대사 경로 매도 종결) | **43** |

→ 약 3주간 매도 70건, 라운드트립 기록 **0건**. 포지션을 지우는 제3의 경로
(`ShadowPortfolioReconciler.java:137`)도 마찬가지로 기록하지 않는다.

### (d) 변경 전 "가짜 손실로 과도하게 멈췄는가" — 검증 불가, 판단 보류
구현자가 인용한 2026-07-30 5건(−5,014,000원)은 ODNO 필터를 쓰던 옛 `FillProcessor` 시절이다.
이 저장소 로그는 2026-08-29부터이고 `trading-db.mv.db`는 실행 중인 앱이 잠그고 있어
`trade_result` 직접 조회도 못 했다. **확인도 반증도 하지 못했다.**

### (e) 판단 — 양쪽 근거 병기
- **위반 아님 쪽**: 현재 증분 영향 0. "모르면 기록하지 않는다"는 원장 오염 방지로 타당.
- **위험 쪽**: 결함 5가 해소돼 체결가가 들어오기 시작하면(WebSocket `H0STCNI9` 또는 실전 전환)
  **이 가드가 그때부터 유일한 손실 누락 경로가 된다.** 실전에서 이건 "멈춰야 할 때 안 멈춤"이다.
  `log.warn` 2종만으로는 사람이 놓친다.
- → **실전 전환 시 재판정 필수.** 실전 전환은 언제나 사람 게이트 G2 선행.

---

## §2. 지시 항목 판정

| # | 항목 | 판정 | 근거 |
|---|---|---|---|
| 1 | 매매 경로 무변경 | ✅ | `git diff --stat -- risk/ strategy/ signal/ backtest/ bucket/` 전부 공백. `OrderEngine.execute` 호출부 8곳 전부 기존(신규 0) |
| 2 | 수량 정합성 | ✅ | `pos.applySell(newlyFilled)`가 가드 **바깥**(`FillStateUpdater.java:340`). 가격 미상이어도 수량은 항상 반영. 회귀 테스트 존재 |
| 3 | 메모리 `Set` 선택 | ⚠️ M-1 / L-2 | 아래 |
| 4 | 매수 분기 | ⚠️ 노트 정정 | 구현자 노트는 "매수 분기 무수정"이라 했으나 **4줄 추가됐다**(`:296-300`, 마커 제거). 매수 체결 처리 자체는 무변경이고 `Position.applyBuy:80-82`의 기존 리셋과 같은 패턴이라 **설계상 일관** — 노트 문구만 부정확 |
| 5 | `BalanceSnapshot` 파급 | ✅ | 구현체는 `KisBalanceClient` 1개(`@Profile("paper")`). `fetchBalance()` 시그니처 불변(record component 추가). `DailyEquity.of(date, equity)` 2-arg 보존 → `BacktestPositionManager.java:96` 그대로, backtest는 `startDeposit=null`. **결정성 무영향** |
| 6 | 프로필 격리 | ✅ | `DailyPnlRecorder:38`·`DailyPnlController:30` 둘 다 `@Profile("paper")`. `@EnableScheduling`은 `SchedulingConfig(@Profile("!backtest"))` 그대로 |
| 7 | 레이트리밋 | ⚠️ M-2 | 횟수가 아니라 **시각**이 문제 |
| 8 | 비밀키 | ✅ | `git diff` 전체 스캔 0건 |

---

## §3. 지적 사항과 조치

| 심각도 | 항목 | 위치 | 조치 |
|---|---|---|---|
| HIGH | `ConsecutiveLossRule` paper 상시 무력화 | `FillStateUpdater.java:258-288` · `FillProcessor.java:109` | **기존 결함** — 후속 과제로 등록. `CLAUDE.md` 룰 표의 "✅ 활성"이 현실과 어긋남(사용자 판단 대기) |
| MEDIUM | 표시 `Set`이 트랜잭션 롤백을 안 따라감 | `FillStateUpdater.java:344` + `:102-107` | 낙관적 락 재시도 시 불완전 합계로 판정 가능. 관측 0건(스케줄 스레드 1개, `OptimisticLock` 로그 0건) — **후속 과제로 등록** |
| MEDIUM | 장외 잔고 API 무가드 호출 | `DailyPnlRecorder.java:62` | ✅ **해소 (리더, 2026-09-15)** — 스케줄을 15:40 → **15:29**(최종스윕 15:28과 마감 15:30 사이, 아직 장중)로 옮기고 `isDuringMarketHoursNow()` 가드 추가. 회귀 테스트 1건 신설(15:40이면 잔고를 부르지 않는다) |
| MEDIUM | `@Transactional` 무효 + 미검증 | `DailyPnlRecorder.java:67` | ✅ **해소** — self-invocation이라 프록시를 안 타 무효였다. 애노테이션을 제거하고 "명시 `save()`로 성립한다"는 근거를 주석으로 남김 |
| LOW | 동일 cron 충돌 (15:40 = `MinuteCandleCollector`) | — | ✅ 부수 해소 — 15:29로 이동하며 충돌 사라짐 |
| LOW | `Set` 무제한 잔존 | `FillStateUpdater.java:64` | 재매수 없으면 프로세스 수명 내내 잔류. M-1 후속 과제에 포함 |
| LOW | Clock 불일치 (같은 PK 공유) | `KisPositionManager.java:106` vs `DailyPnlRecorder.java:64` | 시스템 TZ vs KST 고정. 기존 결함, ③이 의존도 확대 — 후속 과제에 포함 |

### M-2 상세 (해소 근거)
15:40은 마감(`MarketCalendarService.java:28` `DEFAULT_CLOSE = 15:30`) 이후다. 같은 잔고 API를
쓰는 다른 3곳(`RiskMonitor:78`·`TradingScheduler:81`·`SupabaseMirrorScheduler:46`)은 전부 시각
가드가 있고, `SupabaseMirrorScheduler:13-17`에 이유가 명시돼 있다 — *"장외에는 잔고 API가 미러
때문에만 호출되고, 그 실패가 KisApiClient 연속 실패 카운터에 쌓여 아침을 SAFE_MODE로 시작하게
만든다."* 실제 이력: 2026-09-01 19:04·20:23·22:50 SAFE_MODE 3회(임계치 3).

→ **15:29로 이동**하면 장중 호출이 되어 패턴 위반이 사라진다. 15:28 매도가 미체결이면 현금·보유
구분은 흔들리지만 **총자산은 둘의 합이라 순손익은 영향받지 않는다.**

---

## §4. 검증

```
[감사관 직접 실행]
명령: gradle clean test --console=plain
결과: BUILD SUCCESSFUL / 95클래스 tests=634 failures=0 errors=0 skipped=0

[리더 — M-2·M-3 수정 후 재실행]
명령: TRADING_BUILD_DIR=... gradle clean test --console=plain
종료 코드: 0
결과: 95클래스 tests=635 failures=0 errors=0 skipped=0  (신규 가드 테스트 +1)
→ 판정: 통과
```

기준선 618 → 635 (신규 17, 회귀 0). 증분 검산: `FillStateUpdaterTest` +4 · `KisPositionManagerTest` +1
· `DailyPnlRecorderTest` 8 · `DailyPnlControllerTest` 4 = 17.

**테스트 품질**: 신규 4건은 실질적이다 — 스트릭 2에서 가짜 손실 1회가 **3(=차단 발동선)**이 되는
것을 직접 막는지 검증한다. 기존 22곳의 `BalanceSnapshot` 인자 추가는 기계적이며 **단정문을
약화시킨 곳은 없다**(`@Test` 삭제 0건).

**미확인**: 구현자의 "`clean` 없이 `NoSuchMethodError` 9건" 주장은 재현도 반증도 못 했다
(직전 `clean build` 때문에 낡은 클래스가 없었다).

## §5. 확인하지 않은 것 (통과로 적지 않음)

1. **스프링 실기동 배선 미확인** — 웹 컨텍스트를 띄우는 `@SpringBootTest`가 없다.
   재기동 후 **첫 15:29 실행**에서 ① `daily_equity` 행에 `end_equity`/`end_deposit`이 실제로
   UPDATE 되는지 ② `GET /api/daily-pnl` 응답 — 실행 증거로 확인할 것.
2. **2026-07-30 가짜 손실 5건의 실재 여부** — DB 잠금으로 조회 못 함.
3. **과거 오염 데이터 미정리** — `trade_result`의 그 5건은 그대로다(DB 무변경 지시).
