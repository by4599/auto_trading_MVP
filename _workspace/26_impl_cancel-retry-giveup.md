# 26_impl — 취소 무한 재시도 포기 조건 (②)

> 구현 2026-09-26 (`trading-implementer`) · **작업 세션이 완료 기록 없이 끊겨** 이 문서는
> 2026-09-30에 리더가 남은 코드를 직접 읽고 작성했다. 테스트는 리더가 실행해 확인했다.
> 감사는 `_workspace/28_audit_cancel-gate-and-flapping.md`.

## 0. 한 줄

KIS가 취소를 `모의투자 장종료 입니다.`로 거부하면 **다음 개장까지 재시도하지 않는다.**
2026-09-22에 이 응답이 일시적 실패로 분류돼 자정까지 **5,983회**(시간당 604~798회) 헛호출됐다.

## 1. 바꾼 것

| 파일 | 변경 |
|---|---|
| `order/OrderCancelClient.java` | `CancelOutcome`에 **`MARKET_CLOSED`** 추가. 기존 3상수(SENT·NO_OPEN_QTY·FAILED) 의미·이름 불변 |
| `order/KisOrderCancelClient.java` | `classify`에 `장종료` 부분 문자열 검사 추가. **기존 두 분류 뒤에** 넣어 순서 보존 |
| `order/CancelRetryGate.java` (신규) | 취소 시도 관문. `@Profile("paper")` |
| `order/FillProcessor.java` | 취소를 관문 경유로. 빈 결과면 주문 상태 불변 + 다음 폴에서 체결조회 계속 |
| `risk/KisBrokerageApiClient.java` | 청산 준비의 switch에 `MARKET_CLOSED` 분기 추가 — log.error 후 **계속 진행** |
| 테스트 | `CancelRetryGateTest`(신규) · `FillPollerTest`(신규) · `KisOrderCancelClientTest`(수정) |

## 2. 두 겹으로 막는다

1. **우리 달력이 장외면 호출 자체를 하지 않는다** — 거부될 게 확실한 호출로 레이트리밋 유량과
   `KisApiClient` 연속 실패 카운터를 낭비하지 않는다.
2. **달력을 맹신하지 않는다.** 휴장일 목록에 추석이 빠져 있던 2026-09-15 사고가 있었으므로,
   KIS가 스스로 "장종료"라고 답하면 그 말을 진실로 받아 **그 날은** 그 주문의 취소를 더 시도하지 않는다.
   상태는 메모리(`marketClosedOn`: 주문번호 → 거부된 날)로만 기억한다 — `OrderStatus`를 늘리는 쪽이
   더 위험하고, 재기동하면 기동 재동기화가 미체결을 처리한다(OPERATIONS §3 ④).

**막는 것은 취소 시도뿐 — 체결 조회(폴링)는 계속된다.** 그 주문이 이미 체결됐다는 사실을 나중에
알게 될 수 있고, "취소 거부(잔량 없음) = 이미 체결"로 보는 desync 자동복구가 그 경로를 쓴다
(CLAUDE.md 결함 5의 유일한 복구 수단).

## 3. 청산은 억제되지 않는다 (리더가 직접 대조)

`risk/KisBrokerageApiClient.java`는 `CancelRetryGate`를 **타지 않고** `OrderCancelClient`를 직접 부른다.
`MARKET_CLOSED`를 받아도 `log.error("[청산 준비] 장종료로 취소 거부 — 계속 진행")` 후 루프가 계속
돌고 청산 본체가 그대로 진행된다. 기존 `FAILED` 분기와 같은 처리다.
`git diff`상 이 파일의 변경은 **switch 분기 1개 추가**뿐이다.

**타임컷 스윕(15:20·15:24·15:28)도 무영향** — 스윕은 취소 경로를 아예 쓰지 않고
`Signal→RiskEngine→OrderEngine`로 재매도한다. 관문 호출부는 `FillProcessor` 단 1곳이다.

## 4. 검증 (리더가 실행)

```
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build \
      ~/.gradle/wrapper/dists/gradle-9.2.0-bin/11i5gv.../gradle-9.2.0/bin/gradle cleanTest test --console=plain
종료 코드: 0
결과: 812개 중 812개 통과, 실패 0 · 에러 0 · 건너뜀 0
     (직전 기준선 798 + 신규 14 = 812)
```

새 테스트 클래스 3개(`CancelRetryGateTest`·`FillPollerTest`·`KisOrderCancelClientTest`)가 실제로
실행됐음을 `test-results` XML로 확인했다.

⚠ **Red-Green은 확인하지 못했다** — 원 작업 에이전트가 기록 없이 끊겼고, 리더가 되돌려 재실행하지
않았다. 대신 정적 대조로 다음을 확인했다: 판정 순서(NO_OPEN_QTY 우선)를 못박는 회귀 테스트 존재 ·
청산 경로 무억제 · 프로필 일치(`FillProcessor`·`CancelRetryGate` 둘 다 paper).

## 5. 효과 추정

09-22 조건 재현 시 **5,983회 → 최대 1회**(장중에 "장종료"를 받는 그 한 번). 장외에는 달력 게이트가
호출 자체를 막으므로 0회다.

⚠ **실측 아님.** 배포(2026-09-30 21:56)는 장 마감 후였고, 효과 확인은 다음 거래일에 해야 한다.

## 6. 남은 것 (감사 지적 — `BACKLOG.md` [2026-09-30] 등재)

- **M-1**: 장외에는 결함 5의 desync 자동복구가 전혀 발동하지 않는다(예전엔 자정에 복구됐다).
  재기동·다음 개장에 자동 해소. → `TIMEOUT_MINUTES`를 마감까지 남은 시간으로 클램프 권고.
- **M-2**: 장중에 "장종료"를 받으면 억제가 **하루** 지속되고 억제 중 **로그가 한 줄도 없다.**
  → 만료를 "장외→장중 전이" 또는 N분으로, 또는 시간당 1회 요약 WARN.
- **M-4**: ②는 취소만 막는다. 미체결이 남으면 체결조회가 장외에도 시간당 약 1,200회 나간다 —
  "아침을 SAFE_MODE로 시작"을 막는 실질 장치는 ②가 아니라 ①의 (A)·(B)다.
- **L-1**: `marketClosedOn`에 상한·날짜 만료가 없다.
- **미확인**: 실전 계좌의 "장종료" 문구가 부분 문자열 `장종료`에 실제로 걸리는지 — real 전환 시 필수 실측.
