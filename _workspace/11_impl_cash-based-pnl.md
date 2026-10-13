# 11_impl — 현금(예수금) 기반 손익 측정 + 체결가 0 가드

날짜: 2026-09-15 · 브랜치 `backtest/regime-filter-and-validation`
범위: 지시받은 ①②③만. 매매 경로(주문·리스크 판정·청산) 무변경.

---

## 0. 한 줄 결론

가짜 손실이 매매를 멈추던 구멍을 막았고(①), 증권사가 이미 보내주던 예수금을 살려(②)
하루 단위 순손익 원장을 만들었다(③). 전체 테스트 **634개 전원 통과(종료 코드 0)**,
기준선 618개 대비 +16(신규 테스트) — 회귀 0.

---

## 1. 바꾼 파일

### 수정 (main)
| 파일 | 왜 |
|---|---|
| `order/FillStateUpdater.java` | ① 매도 체결가 0 가드. 매도 분기를 `applySellFill()`로 분리 |
| `position/BalanceClient.java` | ② `BalanceSnapshot`에 `deposit` 추가 (3-component record) |
| `position/KisBalanceClient.java` | ② 이미 파싱하던 `dnca_tot_amt`를 스냅샷에 실어 보냄 |
| `position/DailyEquity.java` | ③ `start_deposit` / `end_equity` / `end_deposit` 컬럼 + `recordClose()` |
| `position/DailyEquityRepository.java` | ③ `findByTradeDateGreaterThanEqualOrderByTradeDateDesc` |
| `position/KisPositionManager.java` | ③ 기존 `[DailyEquity] 당일 시작 자산 기록` 장치에 예수금만 얹음 |

### 신규 (main)
| 파일 | 역할 |
|---|---|
| `scheduler/DailyPnlRecorder.java` | ③ 평일 15:40 KST 마감 기록 (`@Profile("paper")`) |
| `dashboard/DailyPnlController.java` | ③ `GET /api/daily-pnl?days=30` (`@Profile("paper")`) |

### 테스트
- 신규: `scheduler/DailyPnlRecorderTest`(7), `dashboard/DailyPnlControllerTest`(4)
- 추가: `order/FillStateUpdaterTest` +4 (체결가 0 가드), `position/KisPositionManagerTest` +1 (시작 예수금)
- 컴파일 정합: `control/TradingControllerTest`, `position/ShadowPortfolioReconcilerTest`,
  `risk/KisBrokerageApiClientTest` — `BalanceSnapshot` 생성자 인자 추가(22곳)

**건드리지 않은 것**: `RiskEngine`·리스크 룰 14개·`LiquidationService`·`OrderEngine`·전략·백테스트 엔진.

---

## 2. ① 체결가 0 가드 — 설계 판단

### 무엇이 문제였나
`FillProcessor.index()`가 KIS 응답의 `avg_prvs`를 그대로 파싱한다. 값이 비면 `0.0`이 되고
`applyFill(orderId, qty, 0.0)` → `realized = (0 - 평단가) x 수량` = **전액 손실**이 기록됐다.
그 손실은 `pos.accrueRealized` → `recordRoundTrip` → `ConsecutiveLossRule`로 흘러
**연속 3회면 실제 매매를 1시간 중지**시킨다. (실측 정황: 2026-07-30 5건 전부 손실, 합계 -5,014,000원)

### 어떻게 고쳤나
매도 분기에서 `fillPrice > 0`일 때만 `TradeResult` 저장 + `accrueRealized`를 한다.
`applySell(newlyFilled)`과 이후 수량 처리는 그대로 — **수량은 언제나 맞춘다**.
매수 분기는 손대지 않았다.

### 라운드트립 판정 생략을 어떻게 추적했나 (지시의 "플래그 금지" 제약)
`FillStateUpdater`의 메모리 `Set<String> pnlGapRoundTrips`를 썼다. `Position`에 새 컬럼을
만들지 않았고, 라운드트립 경계에서 스스로 지워진다:
- 체결가 0 매도 → 종목코드 add
- 전량 청산(수량 0) → remove, 이때 **비어 있지 않았으면 `recordRoundTrip` 호출 안 함**
- 새 라운드트립 시작(수량 0에서 매수) → remove (앞 매매 표시를 물려받지 않음)

**왜 DB가 아닌 메모리인가**: 이건 매매 상태가 아니라 "측정이 가능했나"의 흔적이고,
수명이 라운드트립과 정확히 같다. 재시작하면 잊지만 그때 최악은 **옛 동작으로 되돌아가는 것뿐**
(불완전 합계로 1회 판정)이라 스키마를 늘리는 대가보다 싸다고 판단했다. 코드 주석에도 남겼다.

### 방향성 판단
기록을 건너뛰면 실제 손실이 연속손실 카운터에 안 잡힐 수 있다(= 덜 보수적). 그래도 기록하는 쪽이
더 나쁘다 — **체결가를 모르면 이익인지 손실인지도 모른다**. 가짜 -100%를 기록하면 원장이 오염되고
근거 없이 매매가 멈춘다. 대신 `log.warn` 2종으로 사람이 알아채게 했다.

---

## 3. ② 예수금 노출

`record BalanceSnapshot(double totalAssetValue, double deposit, List<Holding> holdings)`.
`KisBalanceClient`가 `output2.dnca_tot_amt`를 `parseDouble`로 읽어 싣는다(그 필드는 이미
`AccountSummary`에 파싱돼 있었고 버려지고 있었다).

- **백테스트 구현체는 없다** — `grep -rn "implements BalanceClient" src/`는 `KisBalanceClient`
  (`@Profile("paper")`) 한 건뿐. `BacktestPositionManager`는 `PositionManager` 구현체이고
  `BalanceClient`를 쓰지 않는다. 그래서 백테스트 결정성에 닿는 지점이 없다.
- `fetchBalance()` **시그니처는 그대로**다. record에 component를 추가했을 뿐이라
  "인터페이스 시그니처 고정" 규칙에 저촉되지 않는다.
- 기존 테스트 22곳은 deposit을 읽지 않으므로 `0`을 넣었다. 단 `KisPositionManagerTest`의
  "예수금 4,992만 + 삼성전자 1주" 주석이 붙은 fixture는 주석대로 `49_920_000`으로 맞췄다.

---

## 4. ③ 일별 순손익 원장 — 저장 방식 선택 근거

### 새 엔티티가 아니라 `DailyEquity` 확장을 골랐다

| 후보 | 판단 |
|---|---|
| `portfolio_state` (key-value) | **불가**. PK가 키 1개라 날짜별 이력을 담을 수 없다. 원장은 하루 한 행이 필요하다 |
| 새 엔티티 | 불필요. `daily_equity`는 이미 PK=`trade_date`이고 "그날 시작 자산"을 갖고 있다. 새 테이블을 만들면 같은 날짜에 대해 두 테이블이 시작값을 각자 들고 어긋날 수 있다 |
| **`DailyEquity` 확장** | **채택**. 지시대로 기존 장치를 확장. 시작·마감이 한 행에 있어 차이 계산이 조인 없이 끝난다 |

추가 컬럼은 전부 nullable(`Double`) — 이 기능 이전 행과 백테스트 행은 비어 있다.
`ddl-auto: update`라 재기동 시 자동 반영된다. 기존 2-인자 `DailyEquity.of(date, equity)`는
그대로 남겨 `BacktestPositionManager`·`BacktestStateReset` 경로를 건드리지 않았다.

### 시작 쪽은 기존 장치 확장
`KisPositionManager.computeDailyPnl()`이 그날 첫 잔고 스냅샷에서 행을 만들 때 예수금도 같이 넣는다.
**등락률 계산식은 한 글자도 안 바뀌었다** — 같은 행에 값 하나가 더 실릴 뿐이다.

### 마감 쪽은 신규 스케줄러
`DailyPnlRecorder`, 평일 **15:40 KST**. 타임컷(15:15)·최대보유(15:17)·타임컷 재시도 최종
스윕(15:28)·장 마감(15:30)이 전부 끝난 뒤다. 같은 시각에 `MinuteCandleCollector`가 이미
KIS를 호출하고 있어 장외 호출 정책상 새로운 성격의 부담이 아니다.

거부 조건 (전부 "추측해 채우지 않는다"):
- KIS 자격증명 미설정 / 휴장일 → 잔고를 부르지도 않는다
- 시작 기록 없음(그날 앱이 잔고를 한 번도 못 읽음) → 기록 생략 + `log.warn`
- 이미 마감 기록됨 → 생략 (먼저 찍힌 값이 정본)
- 잔고 조회 실패 / 총자산 ≤ 0 → 보류. 예외를 밖으로 내보내지 않는다

### 조회 창구
`GET /api/daily-pnl?days=30` (1~365 제한). 마감 못 찍은 날은 `netPnl: null`,
예수금 기록이 없던 옛 행은 `cashDelta: null` — **0으로 꾸미지 않는다**.

---

## 5. 실행한 명령과 출력 (전부 이번 세션 실행)

### 기준선 (코드 변경 전)
```
명령: gradle clean test --console=plain
종료 코드: 0
결과: 618개 중 618개 통과, 실패 0
```
> ⚠ 참고: `clean` 없이 돌린 첫 시도는 627개 중 9개 실패였는데 전부
> `NoSuchMethodError`(EventBacktestPipeline·OrderSizingService)였다 — 빌드 출력에 남아 있던
> **낡은 클래스** 때문이다. 코드 문제가 아니며 `clean`으로 사라졌다. 이후 전부 `clean`으로 실행.

### Red (가드 구현 전, 테스트만 작성)
```
명령: gradle clean test --tests "com.trading.order.FillStateUpdaterTest"
종료 코드: 1
결과: 25개 중 21개 통과, 실패 4
  - 체결가 0 매도 → 가짜 손실 미기록     : Expecting empty but was: [TradeResult@...]
  - 기존 연속손실 스트릭을 건드리지 않는다 : expected 2.0 but was 3.0   ← 3회 = 1시간 중지 발동선
  - 조각 중 하나라도 체결가 미상이면 판정 생략 : expected 2.0 but was 3.0
  - 다음 정상 매매는 다시 판정된다        : expected 1.0 but was 2.0
```

### Green (가드 구현 후)
```
명령: gradle clean test --tests "com.trading.order.*"
종료 코드: 0
결과: 79개 중 79개 통과, 실패 0
```

### Red 재확인 (가드를 되돌려 버그를 되살림: `fillPrice > 0` → `fillPrice >= 0`)
```
명령: gradle clean test --tests "com.trading.order.FillStateUpdaterTest"
종료 코드: 1
결과: 25개 중 21개 통과, 실패 4 (위와 동일한 4건, 동일한 메시지)
→ 테스트가 이 버그를 실제로 잡는다는 증명
```

### 복구 후 전체
```
명령: gradle clean test --console=plain
종료 코드: 0
결과: 634개 중 634개 통과, 실패 0, 건너뜀 0
```

### 최종 빌드 (컴파일 + 전체 테스트)
```
명령: gradle clean build --console=plain
종료 코드: 0
결과: BUILD SUCCESSFUL, 634개 중 634개 통과, 실패 0
```

집계 방식: `C:/Users/SAMSUNG/auto_trading-build/test-results/test/*.xml`의
`tests`/`failures`/`errors`/`skipped` 속성 합산.

---

## 6. 남긴 한계 (다음 사람이 알아야 할 것)

1. **스프링 빈 배선은 컴파일까지만 검증됐다.** 이 프로젝트에는 웹 컨텍스트를 띄우는
   `@SpringBootTest`가 없다(`@DataJpaTest` 4개뿐). `DailyPnlRecorder`·`DailyPnlController`의
   실제 주입은 앱 기동 시 처음 확인된다. 다만 `DailyEquityRepository`의 새 파생 쿼리
   이름은 `@DataJpaTest`가 리포지토리를 생성하면서 파싱하므로 **이미 검증됐다**.
2. **모의투자 앱(PID 3972)은 옛 코드다.** 8080에 그대로 살아 있고 `trading-db`도 안 건드렸다.
   새 컬럼(`ddl-auto: update`)과 15:40 기록은 **재시작 후에야** 적용된다.
3. **15:40에 앱이 꺼져 있던 날은 마감이 영구히 빈다.** 잔고는 소급 조회가 안 된다.
   캐치업 스케줄은 일부러 넣지 않았다(장외 KIS 호출을 늘리지 않으려고). 그 날은 원장에
   `closed:false`로 남는다.
4. **거래 단위(라운드트립) 손익은 여전히 막혀 있다.** 지시대로 시도하지 않았다 —
   현금 증가분을 특정 매도에 귀속시키려면 그 사이 다른 체결이 없어야 하는데 보장할 수 없다.
   ①은 "가짜 숫자를 안 만든다"까지이지 "진짜 체결가를 구한다"가 아니다. 뿌리(결함 5)는 그대로다.
5. **①의 메모리 표시는 재시작을 못 넘는다.** 2절의 판단 근거 참고. 영향 범위는
   "체결가 미상 조각이 낀 라운드트립이 재시작을 가로질렀을 때 1회"로 한정된다.
6. **과거 오염 데이터는 정리하지 않았다.** 2026-07-30의 가짜 손실 5건은
   `trade_result`에 그대로 있다. 삭제는 범위 밖이고 DB를 건드리지 말라는 지시가 있었다.
   `GET /api/performance`는 당분간 그 5건을 계속 손실로 보고한다.

---

## 7. 발견했지만 손대지 않은 것 (보고만)

- `FillStateUpdater.reconcileFilledFromBalance`는 브로커 잔고로 포지션을 정렬할 때
  `TradeResult`도 `recordRoundTrip`도 남기지 않는다. 전량 매도가 이 경로로 종결되면
  원장에 그 매매가 통째로 빠진다(가짜 손익은 아니므로 ①의 대상은 아니다). 범위 밖이라 그대로 뒀다.
- `KisPositionManagerTest`의 한 fixture는 주석("예수금 4,992만")과 값이 어긋나 있었다.
  이번에 `deposit` 인자를 넣으며 주석에 맞췄다 — 그 줄은 어차피 건드려야 했다.
