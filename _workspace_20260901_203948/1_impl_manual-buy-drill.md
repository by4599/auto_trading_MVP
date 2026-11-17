# 1_impl — 수동 매수 리허설 (005930 10주) 엔드포인트

작업일: 2026-08-12 / 브랜치: backtest/regime-filter-and-validation / 커밋 안 함 (diff만)

## 한 줄 결론

청산 리허설용 포지션을 즉시 만들기 위해 `POST /api/trading/manual-buy-drill`(005930 정확히 10주,
모의 전용, 확인 문자열 필수)를 추가했다. 리스크 룰은 그대로 통과시키고 **사이징(R 역산)만 우회**한다.

## 바꾼 파일

| 파일 | 변경 |
|---|---|
| `src/main/java/com/trading/order/OrderEngine.java` | 모드 게이팅을 `isModeAllowed(Signal)` private 헬퍼로 추출(동작 동일) + `executeManualBuy(Signal, int)` 신규 |
| `src/main/java/com/trading/control/TradingController.java` | 생성자에 `RiskEngine`·`OrderEngine`·`PositionManager`·`Environment` 4개 추가 + `manualBuyDrill()` 엔드포인트 + `isPaperProfile()` |
| `src/test/java/com/trading/order/OrderEngineTest.java` | 신규 4케이스 (고정수량·SAFE_MODE 차단·EMERGENCY_STOPPED 차단·매도/0수량 무시) |
| `src/test/java/com/trading/control/TradingControllerTest.java` | 생성자 변경 반영 + 신규 6케이스 |

## 설계 근거

1. **`execute(Signal)`는 시그니처·동작 불변.** SAFE_MODE/모드 검사만 `isModeAllowed()`로 빼서
   두 경로가 같은 게이트를 공유한다 — 리허설 경로가 안전장치를 우회하지 않게 하기 위함.
2. **아키텍처 규칙 2(흐름 고정) 유지**: 컨트롤러가 `Signal.buy("005930","MANUAL_DRILL")`을 만들고
   `RiskEngine.check(signal, account)`를 먼저 통과시킨 뒤에만 `OrderEngine`을 부른다.
   룰이 막으면 사유를 그대로 응답에 담고 **강제로 뚫지 않는다**(지갑 칸 `BucketBudgetRule` 포함).
   `RiskEngine`·`RiskRule`은 한 줄도 건드리지 않았다(규칙 3).
3. **`KisOrderClient` 시그니처 불변**(규칙 4) — 이미 있던 `buy(code, qty, bucket)` 디폴트 메서드를 쓴다.
4. **범용 매수 도구가 아니다**: 종목·수량은 상수(`005930`/`10`). 요청 바디로 열지 않았다.
5. **모의 전용 이중 잠금**: ① `TradingController`가 이미 `ShadowPortfolioReconciler`(@Profile("paper"))에
   의존해 real에서는 빈 자체가 못 뜬다 ② 그 위에 `Environment.getActiveProfiles()`에 `paper`가 있을 때만
   진행하는 화이트리스트 검사를 추가(요구사항 3).
6. **모드가 RUNNING이 아니면 컨트롤러가 먼저 거부**한다 — OrderEngine이 조용히 삼켜서 "접수됨"이라고
   거짓 응답하는 걸 막기 위함. OrderEngine 쪽 게이팅은 이중 방어로 남겨뒀다.
7. 청산에 새 플래그 없음, `@EnableScheduling` 손대지 않음, 시크릿 하드코딩 없음.

## 검증 증거 (이번에 직접 실행)

```
[전체]
명령: gradle test --console=plain
종료 코드: 0
결과: 78개 테스트 클래스 / 515개 중 515개 통과, 실패 0, 스킵 0
→ 판정: 통과

[Red-Green (핵심 가드가 실제로 잡는지)]
가드 3곳을 일부러 무력화(OrderEngine 모드검사 제거, isPaperProfile()→true, 리스크 거부 분기 무력화)
→ 정확히 아래 4개만 FAILED
  - TradingControllerTest.paper 프로필이 아니면 거부
  - TradingControllerTest.리스크 룰이 막으면 사유를 그대로 돌려주고 주문하지 않는다
  - OrderEngineTest.수동 매수: SAFE_MODE면 차단
  - OrderEngineTest.수동 매수: EMERGENCY_STOPPED면 차단
가드 복구 후 재실행 → 515/515 통과
```

## 남은 한계 / 리스크

1. **스프링 컨텍스트 기동은 자동 검증되지 않았다.** 이 저장소에는 `@SpringBootTest`가 없다(슬라이스
   테스트만 존재). `TradingController` 생성자에 의존성 4개가 늘었으므로, 실제 배선은 다음 paper 기동
   때 처음 확인된다. 타입상 문제는 없다(`Environment`는 컨텍스트가 항상 등록하는 빈, `RiskEngine`·
   `OrderEngine`은 `@Component`, `PositionManager`는 paper 구현체 존재, 순환 참조 없음).
   → paper-ops-verifier가 기동 로그로 확인해줄 것.
2. 대시보드 UI 버튼은 만들지 않았다(요청 범위 밖). 호출은 curl/POST로 한다:
   `{"confirm":"CONFIRM_MANUAL_BUY"}`
3. 체결 확인은 모의 환경 제약(CLAUDE.md 알려진 결함 5번)을 그대로 받는다 — 매수 체결 자체는
   `FillPoller`/재동기화 경로로 잡히지만 즉시 확인은 아니다(최대 ~10분 지연 가능).
4. 매수 후 `StopLossArmer`가 손절선을 다는 경로는 기존 체결 이벤트 경로 그대로다(변경 없음).
   즉 리허설 매수분도 평시와 같은 손절 장착 대상이다.
5. `PositionLimitRule`(종목당 10%)·`BucketBudgetRule` 때문에 계좌 상태에 따라 10주가 거부될 수
   있다. 그때는 "리스크 룰이 매수를 막았습니다 — <사유>"가 그대로 응답에 나온다(설계된 동작).
