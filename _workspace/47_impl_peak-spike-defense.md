# 47_impl — ④ 전고점 튐 방어 (잔고 원래 숫자 기록 + "현금 + 보유×현재가" 대조로 최고 기록 거부)

> 2026-10-11(일) 00:3x~01:1x KST 구현·검증 → (사용량 한도로 중단) → 05:2x 재개: diff 전수 대조·임시 표식 0건·대상/전체 재실행
> · trading-implementer · 감사 다음 단계 `48_audit`
> 작업 위치: worktree `C:\Users\SAMSUNG\Desktop\workspace\auto_trading\.claude\worktrees\agent-af5a99e7e9dbccb46`
> · 브랜치 `worktree-agent-af5a99e7e9dbccb46` · 기준 커밋 `51a30ca`
> (⚠ worktree는 처음에 `a467737`(51a30ca보다 88커밋 뒤)에 있었다 — 깨끗한 상태에서 `git merge --ff-only 51a30ca`로 앞당긴 뒤 시작했다)

## 0. 한 줄 결론

잔고 응답의 총자산이 "D+2 정산 현금 + 보유수량×현재가"로 직접 계산한 값과 **1% 넘게 어긋나면 전고점(최고 기록)으로 인정하지 않고**
WARN(10분에 1줄) + 텔레그램(하루 1통)으로 알린다. 이상할 때·하루 1회는 **증권사가 보낸 원래 숫자 전부**를 로그에 남긴다.
기존 세 값(총자산·예수금·보유)의 뜻과 청산·손절·매수 차단 판정은 하나도 바꾸지 않았다.

## 1. 왜

- 결함 6: KIS 잔고 총자산(`tot_evlu_amt`)이 가끔 튄다. 관측 2건(09-11 17:17 17,047,935원은 상한에 걸림 / 10,890,158원은 통과해
  전고점 영구 오염 → 09-10~09-21 매매 0건). 둘 다 장 밖이었고 그 길은 10-01 `ShadowPortfolioTicker`(장중에만 갱신)로 막혔다.
- 남은 구멍 ①: paper 낙폭 한도 8% → 전고점이 **+5.11%(10,604,158원)** 만 튀어도 매 개장 강제청산. 상한(실측 최대 10,088,806 × 1.15
  = 11,602,127원)은 그 사이를 통과시킨다 → **장중에 튀는 값**을 이번에 막는다.
- 남은 구멍 ②: `KisBalanceClient`가 `dnca_tot_amt`·`tot_evlu_amt` 두 칸만 읽어 원래 숫자가 한 번도 남지 않았다 → 원인 분석 불가.

## 2. 바뀐 파일

| 파일 | 변경 |
|---|---|
| 신규 `position/EquityCrossCheck.java` (89줄) | 대조 판정(순수 계산). 계산값 = D+2 정산 + Σ(수량×현재가), (증권사와 계산값의 차이 ÷ 증권사) > 1% → MISMATCH. 정산 칸 없음·숫자 아님·총자산 ≤0·NaN → UNCHECKED(막지 않음). 차이를 잴 수 없으면(보유 현재가 NaN) MATCH로 넘기지 않음. 허용오차 상수 `TOLERANCE = 0.01` + 근거 주석 |
| 신규 `position/KisBalanceRaw.java` (137줄) | "원래 숫자" 보관 + 스냅샷 조립(순수, HTTP 없음). output2[0] 키=값 전부(응답 순서), output1 행마다 pdno·hldg_qty·pchs_avg_pric·prpr·evlu_amt. `toSnapshot()`의 세 값 파싱은 예전 `KisBalanceClient`와 같은 규칙. `describe()`는 로그용 한 줄, 계좌·키·토큰처럼 보이는 칸 이름은 값 `***` |
| 신규 `position/BalanceRawDiagnostics.java` (129줄) | 기록 전용 진단: 하루 1회 기준선 INFO(KST 날짜, 6개 숫자 + 차이% + 판정) · 불일치 WARN · 직전 성공 대비 ±2% 급변 WARN. 각 경고 처음 1회 + 10분에 최대 1회 + 생략 건수, 둘이 함께면 한 줄로. WARN에 계산값·증권사값·직전 성공 총자산과 시각·원래 숫자 전부 |
| 신규 `position/LogThrottle.java` (48줄) | "처음 1회 + 간격마다 최대 1회 + 생략 건수" 문지기(주입 시계). 3곳(조회 불일치·조회 급변·전고점 거부)에서 쓴다 |
| 신규 `position/SuspiciousPeakAlerter.java` (61줄) | "의심스러운 고점 거부" 텔레그램 하루 1회(불일치·상한 거부가 같은 몫). 장중에 실제로 보낼 때만 하루 몫을 쓴다. 쉬운 말 문구 |
| `position/BalanceClient.java` | `BalanceSnapshot`에 4번째 성분 `equityCheck` + **기존 3인자 생성자 유지(= 판정 불가)** + null → 판정 불가 |
| `position/KisBalanceClient.java` | 얇은 래퍼로: HTTP·결과코드 검사만. output2를 `Map`으로 받아 `KisBalanceRaw`로 조립, 진단 기록(실패해도 조회는 그대로). 생성자에 KST `Clock` 추가(단일 생성자라 스프링 자동 주입). `output2[0]==null`도 "output2 없음" 예외(예전엔 NPE — 예외라는 결과는 같음) |
| `position/Account.java` | `fresh`와 같은 방식의 표시 `equityCheck` + `withEquityCheck()`·`getEquityCheck()`·`isEquityMismatch()`. 공개 4인자 생성자 그대로(= 판정 불가), `asStale()`이 판정 보존, `withEquityCheck()`가 신선도 보존. 비공개 5인자 생성자는 "표시만 바꾼 사본" 3인자로 교체(파라미터 ≤5 규칙) |
| `position/KisPositionManager.java` | `fetchFromKis`가 스냅샷 판정을 Account에 싣는다(+2줄). 캐시·폴백·DB 경로는 그대로 |
| `position/PeakEquityCalibrator.java` | `enum SuspiciousPeakReason` + `default void notifySuspiciousPeakRejected(...) {}` (기존 메서드 무변경) |
| `position/EvidenceBasedPeakEquityCalibrator.java` (269→280줄) | 위 default를 paper에서만 구현 — `SuspiciousPeakAlerter`에 위임, 기존 `BackgroundAlertSender`(감시 스레드 밖) 재사용 |
| `position/ShadowPortfolio.java` | `tick()`: 신선도 관문 → `current <= peak` 조기 반환 → **불일치면 거부**(메모리·DB 그대로, WARN 10분 1줄, 하루 1통) → 상한 거부에도 같은 알림 추가. 시계 받는 생성자(`@Autowired`) 추가, 기존 3인자 생성자 유지(시스템 시계 — 로그 간격에만 씀) |
| 테스트 신규 11파일(시험 클래스 9 + 도우미 2) | `EquityCrossCheckTest`(17) · `KisBalanceRawTest`(12) · `BalanceRawDiagnosticsTest`(11) · `LogThrottleTest`(3) · `KisBalanceClientTest`(5, 가짜 서버 끝단) · `AccountEquityCheckTest`(5) · `KisPositionManagerEquityCheckTest`(5) · `ShadowPortfolioEquityCheckTest`(9, 스프링 배선 포함) · `SuspiciousPeakAlertTest`(8) + 도우미 `LogCapture`, `market/KisApiClientFixture`(시험용 생성자가 market 패키지 전용이라 공개 통로) — 총 +75건 |

기존 테스트는 한 줄도 고치지 않았다(3인자 `BalanceSnapshot` 30여 곳·3인자 `ShadowPortfolio` 그대로 컴파일·통과).

## 3. 설계 선택과 거절한 대안

| 선택 | 이유 | 거절한 대안 |
|---|---|---|
| 판정을 `BalanceSnapshot` 성분으로 싣는다 | 값과 판정이 **같은 응답**에서 나와 어긋날 틈이 없다. 3인자 보조 생성자로 기존 30여 곳이 그대로 "판정 불가" | `BalanceClient`에 `default lastEquityCheck()` — 스냅샷과 판정 사이에 다른 스레드의 조회가 끼면 짝이 틀어진다 |
| Account에는 boolean이 아니라 작은 값 객체(`EquityCrossCheck`) | 거부 WARN에 증권사값·계산값·차이가 필요하다(명세 C). 표시 의미는 `isEquityMismatch()` 하나 | boolean 플래그만 — 로그에 계산값을 못 남긴다 |
| 대조 현금 = D+2 정산(`prvs_rcdl_excc_amt`) | KIS 정의 tot = D+2 정산 + 유가평가. D+0(`dnca_tot_amt`)은 매매 후 이틀간 정상도 어긋난다(시험: 30만원 매수 직후 D+0 식은 3.0% 차이) | D+0 예수금 |
| 정산 칸 없음/숫자 아님 → UNCHECKED | "모른다"를 0으로 메우면 거짓 불일치로 정당한 고점을 막는다 | 0으로 읽기(예전 parseDouble 규칙) — 세 값에는 그대로 쓰고 대조식에만 안 쓴다 |
| 보유 현재가 NaN → MISMATCH | 잴 수 없는 값을 "일치"로 인정하지 않는다(`ratio <= 1%`가 거짓) | NaN 비교가 거짓이라 MATCH로 새는 구현 |
| 텔레그램은 `PeakEquityCalibrator` default → paper 구현만 | 백테스트가 텔레그램을 두드리는 경로를 구조로 막는다(BACKTEST-DESIGN §12, 전고점 경신 알림과 같은 자리) | `ShadowPortfolio`에 알림 도구 주입 |
| `EvidenceBased…`(269줄)에 넣지 않고 `SuspiciousPeakAlerter`로 분리 | 300줄 상한. 위임 1메서드 + 필드만 추가(280줄) | 같은 파일에 하루-1회 상태·문구 추가(>300줄) |
| 하루 몫은 장중에 보낼 때만 소모 | `TelegramNotifier`는 장 밖이면 버린다 — 먼저 도장 찍으면 그날 경보가 사라진다(전고점 경신 알림 감사 M-1의 교훈) | 무조건 도장 |
| `ShadowPortfolio`가 거부 로그를 직접 남김(시계 생성자 추가) | 거부 판정 옆에 증거가 있어야 하고, 어떤 calibrator를 끼워도 거부가 조용히 묻히지 않는다 | 로그까지 paper calibrator로 위임 — 생성자는 안 건드리지만 판정과 증거가 갈라진다 |
| 진단은 `KisBalanceClient` 안(모든 호출자 공통) | 잔고는 `KisPositionManager`·체결 확인·대조기·청산·일별 손익이 모두 부른다 — "직전 성공 조회"는 호출자와 무관해야 한다 | `KisPositionManager`에서 기록 — 다른 호출자의 응답이 빠진다 |
| 진단 실패는 삼킨다(try/catch) | 기록 전용이 잔고 조회(청산·손절 감시의 입력)를 막으면 안 된다 | 그대로 전파 |
| 기존 상한 거부 WARN은 그대로(매 틱) | 명세가 묶으라는 것은 텔레그램. 로그 빈도 변경은 범위 밖(아래 남은 위험 참고) | 상한 거부 로그도 10분 제한 |

## 4. KIS 필드 정의 — 근거와 가정

- **근거(리더 명세 인용)**: `tot_evlu_amt`(총평가금액) = `prvs_rcdl_excc_amt`(가수도정산금액, D+2) + `scts_evlu_amt`(유가평가금액) —
  wikidocs.net/165193(KIS 잔고조회 해설), 공개 저장소들도 같은 식. ⚠ 이번 세션에서 웹 문서를 다시 열어 확인하지는 않았다
  (KIS API 호출 금지·웹 조회 없이 진행). **실제 확인은 재기동 후 1주일의 기준선 로그가 한다**(아래 7절).
- 가정 ①: 모의(VTTC8434R) 응답에도 `prvs_rcdl_excc_amt`가 온다. 안 오면 전부 UNCHECKED(= 예전과 같은 동작, 막지 않음) — 기준선에
  `prvs_rcdl_excc_amt=(없음)`·`UNCHECKED`로 드러난다.
- 가정 ②: 모의 서버도 위 정의대로 계산한다. 다르면(예: tot = D+0 + 유가) **매매 후 이틀간 불일치**가 나서 그동안 정당한 고점
  갱신이 막히고 WARN·하루 1통이 간다. 위험 방향: 헛청산 쪽이 아니라 "전고점이 실제보다 낮게 유지 → 강제정지 기준이 덜 빡빡" 쪽.
- 가정 ③: 보유 행은 한 페이지(연속조회 없음)에 다 온다 — 최대 보유 5종목. 잘리면 계산값이 작아져 불일치로 보인다(막는 쪽).
- 가정 ④: output2 값은 전부 평평한 문자열(정의상). 숫자(JSON number)로 와도 같은 값으로 읽는다(시험 있음).
- 비밀값: 응답 본문 숫자만 남긴다. 상위 필드(`ctx_area_fk100` 연속조회키 — 계좌번호를 품을 수 있음)는 DTO에 받지 않는다.
  끝단 시험이 그런 키가 섞인 실제 모양의 JSON으로 "로그 어디에도 계좌번호·appkey·secret·토큰 없음"을 확인한다.

## 5. 검증 증거

```
[검증 증거 — 기준선(변경 전)]
명령: gradle test --console=plain (TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build-impl47, 51a30ca 그대로)
종료 코드: 0
결과: 936개 중 936개 통과, 실패 0 · 오류 0 · 건너뜀 0 (결과 XML 156개)

[검증 증거 — Red]  (새 클래스·메서드는 동작 없는 껍데기, 기존 클래스는 수정 전 동작 그대로)
명령: gradle test --console=plain --tests EquityCrossCheckTest --tests KisBalanceRawTest --tests LogThrottleTest
      --tests BalanceRawDiagnosticsTest --tests KisBalanceClientTest --tests AccountEquityCheckTest
      --tests KisPositionManagerEquityCheckTest --tests ShadowPortfolioEquityCheckTest --tests SuspiciousPeakAlertTest
      --tests ShadowPortfolioTest --tests KisPositionManagerTest
종료 코드: 1
결과: 132개 중 84개 통과, 실패 48 · 오류 0 — 예상한 48건과 정확히 일치
  EquityCrossCheckTest 10 / KisBalanceRawTest 7 / BalanceRawDiagnosticsTest 8 / LogThrottleTest 3 / KisBalanceClientTest 2
  AccountEquityCheckTest 3 / KisPositionManagerEquityCheckTest 3 / ShadowPortfolioEquityCheckTest 4 / SuspiciousPeakAlertTest 8
  기존 ShadowPortfolioTest·KisPositionManagerTest 실패 0.
  Red에서 통과한 새 시험 27건(새 시험 75 − 실패 48)은 껍데기도 통과하는 것이 맞는 종류다:
  "막아서는 안 되는 경우"(판정 불가 · 2% 미만 · 첫 조회 · 정상/판정 불가 응답은 갱신 · 전고점 아래 · 낡은 스냅샷)
  · 예전 동작 고정(세 값의 뜻 · 오류 메시지 · 3인자 호환 · DB 폴백 · 백테스트 갱신 순서) · 상수 1건(허용오차 1%)
  · 산수 1건(같은 응답을 D+0 식으로 재면 3% 차이) · 구조 1건(4인자 스냅샷이 넣은 판정을 그대로 싣는다).

[검증 증거 — Green (대상)]
명령: 위와 같은 11개 클래스
종료 코드: 0
결과: 132개 중 132개 통과, 실패 0

[검증 증거 — 전체 스위트(구현 직후, 결과 폴더 비우고)]
명령: gradle test --console=plain
종료 코드: 0
결과: 1011개 중 1011개 통과 (01:07 — 변이 시험 전)
```

### 5.1 변이(일부러 고장 낸 뒤 시험이 잡는지) 2종 — 무엇을 바꿨고, 어떻게 되돌렸나

시험이 "그 버그를 실제로 잡는가"를 보려고 운영 코드를 **한 줄씩 일부러 고장 내고** 대상 시험을 돌린 뒤 되돌렸다. 시험 파일은 Red 이후
한 번도 고치지 않았다(마지막 수정 00:54:15 < Red 실행 00:56:56) — 변이 실행은 지금과 같은 시험 코드로 돌았다.

| | 변이 1 — 대조식 현금을 D+0으로 | 변이 2 — 전고점 불일치 관문 끄기 |
|---|---|---|
| 파일·줄 | `KisBalanceRaw.java` 38행 `SETTLED_CASH` | `ShadowPortfolio.java` 149행 `tick()`의 불일치 분기 |
| 원래 코드 | `static final String SETTLED_CASH = "prvs_rcdl_excc_amt";  // 가수도정산금액 (D+2)` | `if (account.isEquityMismatch()) {` |
| 임시 고장 코드 | `static final String SETTLED_CASH = "dnca_tot_amt";  // MUTATION-D0 (임시 — 되돌릴 것)` | `if (account.isEquityMismatch() && Boolean.getBoolean("MUTATION_GATE_OFF_NEVER_SET")) {` (설정 안 한 시스템 속성 → 늘 거짓 = 관문 꺼짐) |
| 대상 시험 | `KisBalanceRawTest` + `KisBalanceClientTest` | `ShadowPortfolioEquityCheckTest` + `SuspiciousPeakAlertTest` |
| 결과 | 종료 코드 1 · **17개 중 실패 3** (01:08:43): 끝단 "매매 직후(D+0≠D+2) 정상 응답 일치" · 순수 "매매 직후 정상 응답 일치" · "D+2 칸이 없으면 판정 불가"(D+0 칸은 있으니 판정해 버림) | 종료 코드 1 · **17개 중 실패 10** (01:09:38): 전고점·저장값 불변 · 거부 WARN 내용 · WARN 10분 제한 · 스프링 배선(시계 생성자로 조립·로그 2줄) · 하루 1통 · 다음 날 다시 · 쉬운 말 문구 · 장 밖 몫 보존 · 전송 스레드 분리 · 백테스트 구현체 무전송 |
| 되돌림 | 01:08:52 원래 줄로 복구 (파일 수정 시각) | 01:09:49 원래 줄로 복구 (파일 수정 시각) |

되돌림 증거 (05:2x 재개 후 다시 확인):
- `grep -rn "MUTATION\|NEVER_SET\|RED 단계\|껍데기\|되돌릴 것\|TODO\|FIXME\|Boolean.getBoolean"` (position 운영·시험 + `KisApiClientFixture`) → **0건**(grep 종료 코드 1).
  (대소문자 무시로 처음 돌렸을 때 `TODO`가 `mapToDouble` 안에 걸린 3건은 오탐 — 대소문자 구분으로 다시 돌려 0건)
- `grep -rn "getBoolean\|System.getProperty" src/main/java/com/trading/position/` → 0건.
- 현재 줄: `KisBalanceRaw.java:38 SETTLED_CASH = "prvs_rcdl_excc_amt"` · `ShadowPortfolio.java:149 if (account.isEquityMismatch()) {`.
- `git diff`(수정 7파일) 전 구간과 신규 5파일을 의도한 변경과 한 줄씩 대조 — 의도 밖 변경 없음.
- Red 단계 껍데기(새 클래스 5개의 동작 없는 판, `Account`·`ShadowPortfolio`·`KisBalanceClient`·`PeakEquityCalibrator`의 껍데기 메서드)는
  전부 실구현으로 덮어썼다 — 위 grep의 "RED 단계·껍데기" 0건이 그 증거.

### 5.2 재개 후 다시 돌린 증거 (최종)

```
[검증 증거 — Green (대상, 05:2x 재실행, 결과 폴더 비우고)]
명령: gradle test --console=plain --tests (위 11개 클래스)
종료 코드: 0
결과: 132개 중 132개 통과, 실패 0 · 오류 0 (결과 XML 26개)

[검증 증거 — 전체 스위트(최종, 05:2x 재실행, 결과 폴더 비우고)]
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build-impl47 LOGGING_FILE_NAME=<scratch>/impl47-test.log gradle test --console=plain
종료 코드: 0
결과: 1011개 중 1011개 통과, 실패 0 · 오류 0 · 건너뜀 0 (결과 XML 174개) = 기준선 936 + 신규 75
시험 로그는 scratch 파일로만 갔다 — worktree에 logs/ 폴더 생성 없음(운영 로그 오염 사고 재발 없음)
```

(두 변이를 되돌린 직후 01:11에도 결과 폴더를 비우고 전체를 돌려 1011/1011·종료 코드 0이었다. 위 05:2x 실행은 diff 전수 대조 뒤 새로 돌린 최종본이다.)

시험 목록(명세 5절 최소 목록 대응):
- 대조 판정: 일치·불일치(09-21 오염값, +5.11% 최소 위험값)·1% 경계 양쪽·방향 무관·판정 불가(칸 없음/빈칸/숫자 아님/null/총자산 0·음수·NaN)
  ·**D+0≠D+2 매매 직후 정상 응답 = 일치**(순수 + 가짜 서버 끝단 둘 다)·미수(음수 정산)·NaN 보유가.
- 원래 숫자 기록: output2 전 키(순서 포함)·output1 5숫자·민감 키 가림·기준선 하루 1회(KST 날짜 바뀌면 다시)·기준선 판정 불가 표기
  ·불일치 WARN 내용(계산값·증권사값·D+2·보유평가·직전 성공값/시각·원래 숫자)·10분 제한과 "2회 생략"·±2%(정확히 2% 포함, 1.99% 제외, 하락 포함)
  ·첫 조회 급변 판정 없음·불일치+급변 한 줄.
- Account: 기본 판정 불가·`asStale` 보존·반대 방향 보존·null → 판정 불가·나머지 값 불변.
- KisPositionManager: 불일치/일치 전달·3인자 스냅샷 = 판정 불가·3초 캐시 보존·DB 폴백 = 낡음 + 판정 불가.
- ShadowPortfolio: 불일치면 메모리·DB 불변·판정 불가/일치면 예전처럼 갱신·거부 WARN 내용과 10분 제한·전고점 아래 불일치는 침묵
  ·낡은 스냅샷은 신선도 관문이 먼저·백테스트 조립 갱신 순서 불변·**스프링 컨테이너(paper·backtest)가 시계 생성자로 조립**.
- 경고: 하루 1회·다음 날 다시·쉬운 말 문구·상한 거부(17,047,935원)도 알림·두 거부가 하루 몫 공유·장 밖은 안 보내고 몫도 안 씀
  ·전송이 호출 스레드 밖·백테스트 구현체는 안 보냄.
- HTTP 경로: `KisMarketDataServiceTest`와 같은 `MockRestServiceServer` + 운영 인터셉터 조립을 `market/KisApiClientFixture`로 열어 덮었다.
- 덮지 못한 것: `KisBalanceClient.recordDiagnostics`의 예외 삼킴(진단이 던지게 만들 주입점이 없다 — 5줄 try/catch).

## 6. 백테스트 무영향 논증

- 백테스트 컨텍스트에서 돌 수 있는 바뀐 코드는 `ShadowPortfolio`(시계 생성자 + `LogThrottle` 생성, `tick()`의 새 분기)·`Account`·
  `EquityCrossCheck`·`PeakEquityCalibrator`(인터페이스 default)뿐이다. `KisBalanceClient`·`KisPositionManager`·`EvidenceBasedPeakEquityCalibrator`·
  `SuspiciousPeakAlerter`·진단은 `@Profile("paper")` 쪽이고, `BalanceClient` 구현체는 paper 전용 `KisBalanceClient` 하나뿐이다.
  `com.trading.backtest` 패키지는 한 줄도 안 바꿨다.
- `BacktestPositionManager`는 공개 4인자 `Account` 생성자 → 판정 불가 → `isEquityMismatch()`는 언제나 false → 새 관문은 절대 안 탄다.
- 상한 거부 분기의 새 알림 호출은 `NoOpPeakEquityCalibrator.isImplausible()`이 언제나 false라 도달하지 않고, 도달해도 default 무동작이다.
- 스프링은 이제 `ShadowPortfolio`를 시계 생성자로 만든다(backtest = `@Primary MutableClock`). 시계는 거부 로그 간격에만 쓰이고
  그 분기는 위 이유로 실행되지 않는다. 전고점 판정은 시계를 보지 않는다.
- 시험: `backtest_assembly_tick_sequence_is_unchanged`(무검증 + 4인자 Account로 5틱 갱신 순서 동일), 배선 시험(backtest 프로필로 컨테이너 조립),
  기존 `ShadowPortfolioTest`의 백테스트 구현체 시험 그대로 통과.
- ⚠ 회귀 앵커(`docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml`) 실행 대조는 **하지 않았다** — backtest-db는 메인 작업 트리에 있고 그 폴더는 건드리지
  말라는 지시라서. 위 논증(도달 불가)이 근거다. 필요하면 감사·검증 단계에서 risk-lab을 돌려 `## 회귀 앵커 대조`가 일치인지 본다.

## 7. 재기동 후 확인 목록 (사람이 재기동 — 이번 작업은 재기동·API 호출·push 없음)

1. **기동 오류 0**: `logs/paper.log`에 `UnsatisfiedDependency`·`BeanCreationException`·`No default constructor` 없음
   (`ShadowPortfolio` 시계 생성자·`KisBalanceClient(KisApiClient, Clock)` 배선).
2. **기준선 INFO 1줄**: `[잔고기준선] 2026-10-1x 첫 조회 — tot_evlu_amt=… prvs_rcdl_excc_amt=… dnca_tot_amt=… scts_evlu_amt=…
   보유평가(수량×현재가)=… 계산값=… 차이=±x.xxx% 판정=MATCH`.
   - "그날 첫 성공 조회"다 — 평일 08:30 자동 기동이면 개장 전 값일 수 있고, 밤샘 보유가 있으면 자정 직후일 수 있다(그래도 식 검증엔 유효).
   - 차이가 수수료 수준(대략 ±0.1% 안)이면 정의대로다. `prvs_rcdl_excc_amt=(없음)`/`UNCHECKED`면 모의 서버가 그 칸을 안 준다는 뜻(보호 없음 = 예전과 같음).
3. **불일치 WARN 유무**: `[잔고원본]`(조회 쪽, 원래 숫자 전부) · `[ShadowPortfolio] 잔고 대조 불일치`(전고점 거부) — 평소엔 0줄이어야 한다.
4. **매매가 있는 날(특히 매수 직후 이틀)** 기준선·WARN을 본다 — D+0≠D+2인 날에도 차이가 1% 안이면 가정 ②가 맞다. 여기서 불일치가 반복되면
   식이 모의 서버와 안 맞는 것이니 허용오차를 넓히지 말고 원래 숫자(output2 전체)로 관계를 다시 찾는다.
5. 텔레그램 `⚠️ [최고 기록 거부]`는 평소엔 오지 않아야 한다. 오면 같은 시각 `[잔고원본]` 줄이 원인 분석 재료다.
6. 1주일 뒤: 기준선 5줄 이상의 차이% 분포로 1% 허용오차를 확정(또는 조정 제안)한다.

## 8. 남은 위험 · 후속 과제 (범위 밖 — 구현하지 않음)

- **불일치 판정을 청산·손절·매수 차단의 관문으로 쓰는 것**(`RiskMonitor`·`StopLossMonitor`·`DailyLossRule` 등): 식이 1주일 기록으로
  검증된 뒤의 후속 과제. 지금은 전고점 인정 여부에만 쓴다.
- **"실제 보유 노출로 상한 계산"**(37_audit 아이디어 — 보유 0이면 오염 원천 차단): 승인 범위 밖, 아이디어로만 남긴다.
- 두 칸이 **함께** 튀면(총자산과 D+2 정산이 같은 만큼) 대조가 일치로 보여 못 막는다. ±2% 급변 WARN이 원래 숫자는 남긴다.
- 상한 거부 WARN은 예전처럼 매 틱(장중 1초) 찍힌다 — 상한 위로 튄 값이 장중에 계속되면 로그가 많아진다(텔레그램은 하루 1통). 빈도 조정은 별건.
- 생략 건수는 "다음 기록"에 붙는다 — 이상이 10분 안에 끝나면 마지막 몇 회의 생략 건수는 남지 않는다.
- 하루 1통 몫과 기준선 날짜는 메모리라 재기동하면 그날 한 번 더 갈 수 있다(전고점 경신 알림과 같은 결정).
- 파싱 실패 WARN("잔고 숫자 파싱 실패")의 로거 이름이 `KisBalanceClient` → `KisBalanceRaw`로 바뀌었다(문구·동작 동일).
- CLAUDE.md "결함 6" 서술 갱신은 하지 않았다(에이전트가 CLAUDE.md를 고치지 않는다) — 감사·재기동 확인 뒤 리더/사용자 판단.
