# 48_audit — ④ 전고점 튐 방어 (커밋 `dcd95a3`, 부모 `51a30ca`) 감사

> 2026-10-11(일) · risk-auditor · 코드 수정 0 · gradle 0 · 앱·운영 DB·API·KIS 접근 0 · heredoc 0.
> 리더가 보고 전문의 요지를 이 경로에 보존했다. 구현 기록은 `47_impl_peak-spike-defense.md`.
> 범위: 24파일 — 주 코드 12(전부 `position`), 시험 11(전부 신규), 문서 1.

## 0. 판정 — CRITICAL 0 · HIGH 0 · MEDIUM 2 · LOW 7 → 병합 가능 (조건 3)

1. cherry-pick 뒤 메인 트리(51a30ca + ① + ④)에서 **전체 시험을 한 번 더** 돌려 증거를 남긴다(지금 증거는 51a30ca + ④ 단독).
2. 재기동 후 확인(§4의 3~6번)이 끝나기 전에는 결함 6을 "해소"로 적지 않는다(M-1·M-2).
3. CLAUDE.md 결함 6은 "장중에 **총자산만** 튀는 한 갈래를 막음 / 정산 칸이 같이 튀거나 판정 불가인 응답은 못 막음 / 모의 서버에서 식이 맞는지 검증 대기"로 적는다.

## 1. 판정표

| 심각도 | ID | 항목 | 위치 | 내용 |
|---|---|---|---|---|
| MEDIUM | M-1 | 대조식이 모의 서버에서도 맞는지 미검증 → 전고점이 멈출 위험 | `EquityCrossCheck.java:57-67` · `KisBalanceRaw.java:38,79-80` · `ShadowPortfolio.java:149-152` | "총자산 = D+2 정산 + 유가평가"는 KIS 정의 근거가 있으나 모의(VTTC8434R)가 지키는지는 모른다. 안 지키면(예: 모의 총자산이 D+0 기준) 매매 뒤 이틀 또는 늘 불일치 → 전고점이 오르지 않는다. 결과 방향은 헛청산이 아니라 "강제정지 기준이 느슨해짐"(최악: 전고점이 멈춘 사이 크게 올랐다 떨어지면 원래 정지선보다 아래에서야 멈춤). D+0 식이 맞다면 "매수 직후 총자산이 매수금액만큼 부푼 것"이 오히려 튐의 후보 원인이고 관문은 그걸 막는다 — 다만 그 이틀의 진짜 신고점도 같이 막힌다. 감지 수단: 기준선 INFO의 판정·차이, WARN 10분 1줄, 하루 1통 |
| MEDIUM | M-2 | 막는 범위의 한계 — "합계 = 성분 합"만 본다 | `EquityCrossCheck.java:59-60` · `KisBalanceRaw.java:129-135` · `EquityCrossCheckTest.java:56-64` | (a) 정산 칸(`prvs_rcdl_excc_amt`)이 총자산과 함께 튀면 "일치"로 통과(47_impl §8도 인정) (b) 정산 칸을 비우거나 숫자 아닌 값을 주면 판정 불가로 통과하고, 그 파싱 실패는 WARN 없이 기준선 INFO에만 보인다. 9-21 오염(10,890,158원)은 원래 숫자가 없어 어느 쪽이었는지 모른다 — 시험 `contamination_of_2026_09_21_is_a_mismatch`는 "총자산만 튐" 가정의 재현. 남은 방어: 기존 상한(×1.15) · 전고점 경신 텔레그램(+1%) · 새 ±2% 급변 WARN(원래 숫자 기록) |
| LOW | L-1 | backtest 실제 조립·앱 전체 기동의 실행 증거 없음 | `ShadowPortfolioEquityCheckTest.java:185-209` · `ClockConfig.java:19-22` · `BacktestClockConfig.java:20-23` | 배선 시험은 시계 빈 1개 컨테이너라 backtest의 실제 상황(`clock` + `@Primary mutableClock`)을 재현하지 않는다. 같은 패턴(`BacktestPositionManager`·`MarketCloseRule`의 `Clock`)이 매일 쓰이므로 위험 낮음 |
| LOW | L-2 | (가) "원래 숫자 전부"의 범위 | `KisBalanceRaw.java:45-50,95-99` · `KisBalanceClient.java:121-126` | output2[0]은 전부, output1은 행마다 5칸만, 상위 msg_cd·msg1은 안 남긴다. 정산 쪽 튐의 원인을 찾으려면 당일 매매·주문가능 칸(thdt_buyqty·thdt_sll_qty·ord_psbl_qty·pchs_amt 등)이 필요할 수 있다 |
| LOW | L-3 | 급변 WARN 10분 제한이 두 번째 사건의 원래 숫자를 삼킴 | `BalanceRawDiagnostics.java:61,92-96` | 첫 WARN 뒤 10분 안의 다른 튐은 "n회 생략"으로만 남는다(09:00 개장의 정상 ±2% 변동이 몫을 먼저 쓸 수 있음) |
| LOW | L-4 | 하루 1통 몫이 전송 실패에도 소모 | `SuspiciousPeakAlerter.java:40-44` · `TelegramNotifier.java:89` | 15:30 직전 대기열에 들어가면 배달 시점의 장 시간 판정에 걸려 버려질 수 있는데 몫은 이미 씀(대기열 가득 32건도 같음). 기존 전고점 알림과 같은 성질 |
| LOW | L-5 | 허용오차 근거 문구가 계좌 상태에 따라 달라지는 값 | `EquityCrossCheck.java:39-40` · `EquityCrossCheckTest.java:66-72` | "+5.11%"는 계좌가 전고점보다 3.3% 아래일 때만 맞다(10,604,158×0.92 = 9,755,825). 정확한 규칙: "받아들이면 낙폭이 8% 이상 되는 총자산 단독 튐은 계산값과의 차이가 항상 8% 이상" → 1%는 낙폭 상태와 무관하게 충분(근거는 더 강함). 시험 이름 `smallest_dangerous_contamination`의 실제 차이는 4.86% |
| LOW | L-6 | 시험용 3인자 생성자가 시스템 시계 사용 | `ShadowPortfolio.java:36-40` | 운영·백테스트는 `@Autowired` 4인자 생성자라 영향 없음 |
| LOW | L-7 | 파일 크기 상한 근접 | `EvidenceBasedPeakEquityCalibrator.java` (280/300줄) | 다음 수정 때 분리 |

## 2. 핵심 근거
1. **흐름·경계 — 위반 없음**: 주 코드는 `position` 12파일뿐, `risk·order·strategy·signal·scheduler·backtest` 0줄. `RiskEngine`·`LiquidationService`·리스크 룰 무변경. `KisBalanceRaw.toSnapshot`(:71-82)이 옛 파싱 규칙을 그대로 옮겨 세 값(총자산·예수금·보유)이 같고, `KisPositionManager`(:139-141)는 표시만 붙인 사본. `RiskMonitor`·`StopLossMonitor`·`DailyLossRule`·`DailyPnlRecorder`는 새 판정을 읽지 않는다(`position` 밖 사용처 0). 값 아닌 차이 2개: 파싱 실패 WARN 로거 이름, output2[0] null일 때 NPE → IllegalStateException(호출자 처리 동일). 새 관문은 전고점을 "덜 올리는" 쪽으로만 작동 — 청산·매수 차단이 더 일어날 길이 없고, 자동 갱신 경로는 `tick()` 한 곳뿐.
2. **시그니처 — 규칙 4 위반 아님**: `fetchBalance()`·`PositionManager` 불변. `BalanceSnapshot` 4번째 성분 + 공개 3인자 생성자(판정 불가) + null → 판정 불가, 레코드 분해 사용처 0. `PeakEquityCalibrator`는 enum·default만 추가(`NoOp` 무수정 컴파일). `Account` 공개 4인자 생성자 불변, `asStale()`↔`withEquityCheck()` 양방향 보존(시험 있음).
3. **스프링 조립 — 이상 없음**: `ShadowPortfolio`는 4인자에 `@Autowired`. paper 시계 빈은 `ClockConfig.clock()`(KST) 하나, backtest는 `@Primary mutableClock`이 이김(기존 빈들과 같은 방식). `KisBalanceClient(KisApiClient, Clock)` paper 전용. 새 스프링 빈·새 시계 빈 0.
4. **백테스트 무영향 — 논증 맞음**: `BacktestPositionManager`(:86-87)는 4인자 `Account` → 늘 판정 불가 → 불일치 분기 미도달. `NoOp.isImplausible`이 늘 false라 상한 분기·새 알림도 미도달(닿아도 무동작). `tick()`의 갱신 조건·저장·로그·알림 불변, `LogThrottle` 생성 시 시계를 읽지 않아 가상 시계 상태 불변. `backtest` 패키지는 새 API를 쓰지 않는다.
5. **대조 판정**: D+2(`prvs_rcdl_excc_amt`)를 쓴다(소스 :38, 컴파일 상수 확인). 칸 없음·빈칸·숫자 아님·총자산 0 이하·NaN·무한 → 판정 불가(막지 않음). 보유 현재가 NaN → 불일치(막는 쪽). 수량 0 행 제외·여러 종목 합산(시험 있음). 1%: 총자산 단독 튐이 위험하려면 차이가 8% 이상이어야 하므로 8배 여유, 통과 가능한 최대 오염은 실자산의 약 +1%(낙폭 여유를 최대 약 1%p만 깎음). 정당한 고점이 막히는 모든 경로의 결과는 "전고점이 낮게 유지 → 강제정지 기준이 느슨해짐"이고 헛청산·주문 차단으로 가는 길은 없다.
6. **(가) 원래 숫자 기록**: 계좌번호가 든 연속조회키(ctx_area_fk100)는 받지 않음, 칸 이름 가림 패턴, 끝단 시험이 실제 모양 JSON으로 로그에 계좌·appkey·secret·토큰이 없음을 확인(INFO 이상만). 로그 양: 기준선 KST 하루 1회, 불일치·급변 WARN 각 10분 제한, 전고점 거부 WARN 10분 제한(기존 상한 거부 WARN은 원래 매 틱). 감시 스레드에 새로 늘어난 동기 작업은 로그·메모리 계산뿐, 텔레그램은 `BackgroundAlertSender.send`(즉시 반환) → `TelegramNotifier` 대기열. 하루 1통: 장 밖이면 몫을 안 쓰고, 불일치와 상한 거부가 몫을 공유.
7. **시험 증거(직접 셈)**: XML 174개 · 1011건 · 실패·오류·건너뜀 0, 신규 9클래스 XML 18개 75건(1011−75 = 936, 174−18 = 156). 실행 KST 05:25:17~05:25:39, 커밋 05:32:48, 커밋된 소스 마지막 수정은 변이 되돌림(01:08:52·01:09:49) 이전·이후와 맞고 `ShadowPortfolio.class`는 01:11:04(되돌린 뒤), 작업 트리 깨끗 → 커밋된 코드의 결과. 클래스 바이트에 변이 흔적 0. 변이 재계산: D+0이면 3/17 실패, 관문 끄면 10/17(4+6) — 주장과 일치(Red·변이 실행 기록 자체는 덮어써져 자기 보고). 기존 시험 수정·삭제·이름 변경 0.
8. **규칙**: 최대 280줄, `tick()` 44줄, 신규 파라미터 5개 이하, 하드코딩 비밀값 없음(시험의 `50000000-01`은 yml 주석의 공식 예시값).
9. **합치기**: 부모 `51a30ca`(origin/master `a467737`에서 생성 → ff 앞당김 → 커밋 1개). ① 파일과 겹침 0, 의미 충돌 없음(① 시험은 `Account` 4인자·`asStale()`만, ① 주 코드는 `isFresh()`·`getPositions()`·시계 주입만, 새 시계 빈 없음). `_workspace` 번호 겹침 없음.

## 3. 회귀 앵커 재실행 — 필요 없음 (병합 조건 아님)
백테스트에서 도는 바뀐 코드는 `Account`(판정 불가 고정), `ShadowPortfolio`(생성자 + 늘 false인 분기), `PeakEquityCalibrator` default(무동작)뿐 — 전고점 갱신 순서·값·저장 불변. 빈 곳은 L-1(backtest 컨텍스트가 새 생성자로 실제로 뜨는지의 실행 증거)뿐이고, 같은 패턴이 매번 쓰여 위험 낮음. 다음에 백테스트를 돌릴 때 "기동 + `## 회귀 앵커 대조` 일치"를 함께 확인하면 된다(선택).

## 4. 재기동 후 확인 목록
1. `logs/paper.log`에 `UnsatisfiedDependencyException`·`NoUniqueBeanDefinitionException`·`BeanCreationException`·`No default constructor` 0건
2. `[ShadowPortfolio] peakEquity 복원:` 값이 재기동 전과 같음
3. `[잔고기준선]` 한 줄: `prvs_rcdl_excc_amt` 값이 있고 `판정=MATCH`, 차이 ±0.1% 안. MATCH가 아니면 차이(원)가 `dnca_tot_amt − prvs_rcdl_excc_amt`와 비슷한지(비슷하면 모의가 D+0 식 — M-1). `(없음)`·`UNCHECKED`면 보호 꺼짐(예전과 같음) — 어느 쪽이든 바로 보고
4. 평소 `[잔고원본]` WARN · `잔고 대조 불일치` WARN · 텔레그램 "최고 기록 거부" 0
5. 매수·매도가 있는 날과 그 뒤 이틀 동안 차이가 1% 안
6. 신고점이 나는 날 `peakEquity 경신` INFO가 여전히 찍힘(관문이 전고점을 얼리지 않는지)
7. ±2% 급변 WARN이 나오면 그 줄 보존(원인 분석 재료)
8. 1주 뒤 기준선 5줄 이상으로 1% 허용오차를 확정하고 결함 6 서술 갱신(조건 3)

## 5. 범위 밖 참고 (집계 제외)
- **R-1**: 그날 첫 신선 잔고가 아무 관문 없이 "그날 시작 자산"(`daily_equity`)으로 저장된다(`KisPositionManager.java:151-165`). 보유가 있으면 장 밖에도 조회하고 대시보드 조회로도 생긴다. 그 값이 튄 값이면 장중 `RiskMonitor`의 일일 손실 −5% 강제청산(`RiskMonitor.java:102-110`)이 헛발동할 수 있고, 상한 근거(MAX 시작자산)도 느슨해진다. 확률은 낮지만 결과가 크다 — 별건 등록 권고.
- 후속 아이디어(사용자 승인 필요): "같은 값이 연속 2회 조회될 때만 전고점 인정" — 어느 칸이 튀든 일시적 튐을 거른다(M-2 보완).
- 이 커밋은 paper 안전장치 보강이며 실전 승격 근거가 아니다. 실전 전환은 TRADING-RULES-AUDIT CRITICAL 해소와 게이트 G2(사람) 선행.

## 6. 사용자에게 전할 쉬운 말
증권사가 가끔 우리 "총재산"을 부풀려 알려주고, 그 숫자가 "최고 기록"으로 굳어서 7일 동안 매매가 멈춘 적이 있다. 이번에는 "현금 + 주식값"을 우리가 직접 더해 보고, 증권사 숫자와 1% 넘게 다르면 최고 기록으로 믿지 않게 했다(이상할 땐 원래 숫자를 적어 두고, 알림은 하루 한 번). 다만 현금 칸까지 같이 틀리게 오면 못 거르고, 이 계산법이 모의 서버에서도 맞는지는 다시 켠 뒤 며칠 기록을 봐야 안다.
