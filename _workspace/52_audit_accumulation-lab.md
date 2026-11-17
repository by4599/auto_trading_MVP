# 52_audit — "흔적레이더" 매집 흔적 백테스트 랩 감사 (51_quant 커밋 전)

> 2026-10-13 08:10 KST · risk-auditor · 코드 수정 0 · gradle 0 · 앱·운영 DB·API·KIS 접근 0.
> 리더가 보고 전문의 요지를 이 경로에 보존했다. 범위: 소스 신규 7 · 수정 7, 시험 신규 5 · 수정 1, 문서 §18. 이 주제의 첫 감사.
> 운영 메모: 감사 중 명령 하나에 금지 확장자 문자열이 들어가 멈췄다 — 출력 없음, 실행된 것 없음, 해당 파일 미열람, `.git/index.lock` 없음.

## 0. 판정 — CRITICAL 0 · HIGH 0 · MEDIUM 0 · LOW 8 → 커밋 가능 (막는 조건 없음)
권고: 커밋 전 L-2·L-3·L-4를 문서에 한 줄씩. L-1은 원하면 오프라인 재실행 1회로 닫을 수 있다.

## 1. paper 무영향 — 확정
- 새 빈은 전부 backtest 전용: `AccumulationProperties:13-14` · `AccumulationStrategy:27-28` · `AccumulationLab:26-27`에 `@Profile("backtest")`. `AccumulationRule`(record)·`AccumulationSignalCalculator`(정적)·`AccumulationLabSummary`·`TradeOverlap`·`LabReportTemplates`는 빈이 아니다.
- 수정된 빈도 전부 backtest 전용: `DailyBarSimulator:36-37` · `StrategyToggles:21-22` · `CandidateLabRouter:19-20` · `LabComparisonReportWriter:24-25` · `CandleBackfillService:32-33`. Accumulation*를 참조하는 main 파일 9개가 모두 backtest 빈이거나 비빈 → paper 빈이 backtest 빈에 기대는 경로·기동 실패 경로 없음.
- 프로필 없는 유일한 클래스 `BacktestDataProperties`는 `@ConfigurationPropertiesScan`(`TradingApplication:20`)으로 원래 paper에서도 생긴다. 이번 추가는 `offline=false` 필드 하나(`:139`)이고 읽는 곳은 `CandleBackfillService:123`(backtest 전용)뿐. paper `MinuteCandleCollector`(`@Profile("paper")`)는 `getSymbols`·`getEventSymbols`·`getCandidateSymbols`만 읽는다(무관).
- git: `src/main/resources`(yml) 변경 0, risk·order·position·bucket·market·scheduler·signal·config 변경 0, `SchedulingConfig:33-34` 그대로, 새 JPA 엔티티 없음(`trading-db` 스키마 무영향).
- 컴파일: 마지막 소스 수정 04:46:36 → 04:48 전체 컴파일·시험 통과, 이후 바뀐 src 0개.

## 2. 판정표
| 심각도 | ID | 항목 | 위치 | 내용 |
|---|---|---|---|---|
| LOW | L-1 | 연구 숫자를 낸 코드와 최종 코드 사이 검증 공백 | `DailyBarSimulator.java` (최종 저장 04:46:36) | 백테스트 3회(JVM 04:03:17 / 04:26:05 / 04:34:46)가 끝난 뒤 한 번 더 저장됐다. 구현 담당은 "주석 1줄"이라 하나 원본이 없어 독립 확인 불가. 스위치 OFF 경로는 HEAD 대비 정적 diff와 리포트 바이트 동일로 증명, 스위치 ON(매집 칸 숫자)은 정황 증거(본·후속 실행 A3 동일 재현, 최종 코드로 시가 경로 시험 6/6). 판정·paper 영향 없음 |
| LOW | L-2 | 사전 등록 표기 누락 — 민감도 칸이 관문 길이도 바꿈 | `AccumulationRule.java:67-69` · `AccumulationSignalCalculatorTest.java:356-362` | 평균창 16·24 칸은 데이터 관문도 31봉 → 27·35봉으로 바뀐다(시험이 고정). 51_quant §0.4는 "관문 31봉"을 고정 상수로 적었고 §5·§18에 언급 없음. 코드는 03:36 작성으로 결과보다 먼저라 사후 조정은 아님. 판정 영향 없음 |
| LOW | L-3 | 사전 등록 기록의 정밀도 | 51_quant §0.5-5 · §0 머리말 | ① C도 기준 1~4를 통과했는데 "두 가족 이상이면 리더 보고" 조항 처리 기록이 없다(C는 참고용이라 제외는 타당 — 근거를 적을 것) ② "03:4x — 코드 작성 전" 문구가 `AccumulationRule.java` 최종 저장 03:36:34와 어긋난다(저장 방식상 사전 등록 시각을 파일 이력으로 검증 불가). 판정 영향 없음 |
| LOW | L-4 | 종목 순차 처리 근사 — 크기 미측정 | `BacktestRunner.java:103-105` · `DailyBarSimulator.java:178-194` | 리틀 법칙 추정 동시 보유 평균(한도 5): A2 약 1.5~1.9 · A3 약 0.3~0.4 · **C 약 2.8~3.6** · D0 약 3.6~4.5. A는 영향 작음(동의), C는 작지 않을 수 있는데 언급 없음. 방향은 대체로 낙관(09:00 이후 청산이 자리·현금을 먼저 비움), 일부 비관(앞 종목의 늦은 손실로 연속손실 차단이 걸리면 09:00 진입까지 막힘). 결론("D0보다 낫지 않다")은 이 편향을 빼면 더 단단해지는 방향 |
| LOW | L-5 | 오프라인 + 기준선 기록 조합의 약점 | `CandleBackfillService.java:123-126` · `CandleCoverageChecker.java:96-106` | offline이면 종목별 구멍을 안 채우고, 커버리지 관문은 "전 종목 중 가장 늦은 날"만 봐 종목별 꼬리 결측을 못 본다(§14.5 기지 한계) → `offline=true` + `write-baseline=true`면 구멍 난 기준선이 찍힐 수 있다. 이번엔 write-baseline=false, 결과 바이트 동일. 이 조합 거부를 BACKLOG에 |
| LOW | L-6 | 문서 수치 오기 | `CandidateLabRouter.java` | 실제 296줄(“292줄” 기재 오류), 상한 안 |
| LOW | L-7 | 파일 크기 | `AccumulationSignalCalculatorTest.java` | 364줄(시험 파일, 기존에도 300줄 넘는 시험 11개) |
| LOW | L-8 | 시험·앵커 보강 여지 | `DailyBarNextOpenEntryTest.java:201` · `LabReportTemplates.java:102` · `AccumulationLab.java:106-108,115` | ① 시가 진입분 당일 손절 시험은 고정% 손절로만(ATR 손절은 이벤트 발행기가 가짜라 안 돈다) ② D0 앵커 숫자가 문구에 복제돼 사람 눈으로만 대조(자동 대조 `BaselineDriftReporter` 같은 장치 없음) ③ 매집 스위치가 켜진 채 남으면 다른 랩 진입도 조용히 09:00 경로로 바뀐다 — finally 복원과 "JVM 하나에 모드 하나"뿐, 시험 없음 |

## 3. 항목별 판정 (요지)
1. **paper 무영향 — 통과(확정)**, 위 1절.
2. **아키텍처 — 통과**: `strategy` 패키지에 OrderEngine·KisOrderClient·RiskEngine 참조 0(주석만). `AccumulationStrategy.evaluate`(`:50-63`)는 읽기만 하고 `List<Signal>` 반환, 이름표 TREND(돈치안과 같음). 새 시가 경로도 `riskEngine.check`(`:188`) → `orderEngine.execute`(`:193`). RiskEngine·LiquidationService·인터페이스 무변경, `trading.bucket.enabled` 미사용, `@EnableScheduling` 그대로.
3. **기존 백테스트 경로 불변 — 통과**: HEAD 대비 diff는 import·상수·필드·생성자 인자·분기·새 메서드뿐, OFF 분기는 기존 `checkEntry` 그대로(`:106-110`), `checkEntry`·`resolveEntryPrice`·`firesAt` 무수정. 실행 확인 ① 랩 안 D0 24창 표가 09-22 `REPORT-RISKLAB-DONCHIAN-BRACKET-BRACKET-20260922-0146.md` BL 표와 diff 0(본·후속 실행끼리도 0) ② risk-lab이 09-07 `REPORT-RISKLAB-MA-P3-20260907-2344.md`와 판정 요약~창별 표 diff 0, `## 회귀 앵커 대조` 5지표(781 · 1.959 · 0.0130 · 0.0987 · 0.5186) 일치. 기준선 yml md5 `c2d8460d4874b99c4eaecc5e76d0b800`, 수정 시각 2026-08-17 그대로.
4. **오프라인·KIS 미호출 — 통과(L-5)**: 기본 false(`offlineDefaultsToFalse`), KIS 호출부(`:130-132`)보다 앞에서 빠짐(`backfillAll`·`backfillExtra` 모두), 시험으로 무호출/대조군 호출 확인. 실행 로그 3개 각각 "오프라인 실행" 62줄, KIS·토큰·UnknownHost·refused·I/O error·ERROR 0줄. `--kis.base-url=http://openapivts.invalid:9`는 실행 인자뿐(src·docs에 없음), `.invalid`(RFC 6761 예약)·포트 9(버림) + 더미 키·별도 JVM·별도 `backtest-db` → 위험 없음. yml에 넣지 말고 실행 레시피로만.
5. **선견편향 — 통과(근사는 L-4)**: 전략은 `getDailyCandles`(`headMap(simDate, false)` — t+1일에 t일까지만)만 본다. 진입 t+1 시가 + 슬리피지 0.1%, 09:00 판정(`buysAtOpenAtNine`), 당일 봉은 시가로 잘림(`strategySeesOnlyTheOpen`)·인자 미사용(`ignoresTodayCandleArgument`), 장 시작 보유 여부를 이월 손절보다 먼저 계산해 되사지 않음(`noReentryWhenHeldAtOpen`), 지수 MA120도 날짜 기준 `headMap`, 시스템 시계 직접 호출 0.
6. **판정의 정직성 — 통과(L-2·L-3)**: 리포트·51_quant §3·§4·§6·§18 숫자 일치. 가족 재계산: A의 표본 충분 칸 A2·A3·A3-NT 중 A2·A3 통과(과반), PF 중앙값 2.03 ≥ 1.92이나 기대값 중앙값 1.303% < 1.340% → "조건부" 맞음(추세 칸 둘만 봐도 1.3305% < 1.340%). 후속 칸을 넣으면 1.358%로 뒤집히는데 판정에서 뺀 것은 사전 등록 취지대로이고 새 전략에 **불리한** 쪽 적용 — 사후 유리 조정 정황 없음. 최고 칸(A4 1.755%, B4·11 PF 5.65, A3w24 PF 3.10)을 고르지 않았다. 본 실행 JVM 04:03 기동, 04:22 리포트 10칸 = 현재 `grid()`, 04:25 수정은 후속 칸 추가(후속 실행 04:26 기동이 최종본 사용).
7. **시험 증거 — 통과**: XML 197개 · tests 1150 · failures 0 · errors 0 · skipped 0(리더 값과 동일), 묶음 기록 04:48~04:49, XML 04:49:07 > 마지막 소스 수정 04:46:36. 신규 44건 = 23+6+6+3+6, 경계값(2.99배 미발화, 정확히 +3.00% 발화, ±3.5% 미발화, t−25 거래량 0 차단, t−31 비차단, 분할 −80% 차단, 상한가 +30% 통과)을 실제로 잡는다. 공백은 L-8.
8. **규칙 — 통과(L-6·L-7)**: main 전부 300줄 미만(최대 296), 새 함수 최장 약 31줄, 비밀값 0.

## 4. 참고 (이번 변경이 만든 것 아님, 집계 제외)
- I-1: `BacktestMarketDataService.java:114`가 `floorEntry(simDate)`라 그날 아직 처리 전인 보유 종목을 **당일 종가**로 평가한다(`BacktestPositionManager.java:78`) → 09:00·10:00 진입 시점의 사이징·일일손실·MDD 매수 차단에 같은 날 미래 가격이 조금 섞인다(D0·새 칸 모두).
- I-2: `KisProperties.java:30`의 모의 URL 검사는 `contains("openapivts")` 부분 문자열 — 실전 URL을 막는 목적에는 충분.
- 이번 연구는 승격 근거가 아니다. A동 진입을 바꾸려면 ADR-001 재논의와 게이트 G2(사람)가 먼저.

## 5. 커밋 범위 권고
- 넣을 것: `strategy/{AccumulationRule, AccumulationSignalCalculator, AccumulationProperties, AccumulationStrategy}` · `backtest/{AccumulationLab, AccumulationLabSummary, TradeOverlap, DailyBarSimulator, StrategyToggles, CandidateLabRouter, LabReportTemplates, LabComparisonReportWriter, BacktestDataProperties, CandleBackfillService}` · 시험 `strategy/{AccumulationSignalCalculatorTest, AccumulationStrategyTest}` · `backtest/{DailyBarNextOpenEntryTest, CandleBackfillOfflineTest, AccumulationLabProfilesTest, DailyBarSimulatorTest}` · `docs/BACKTEST-DESIGN.md`(§18 +80줄뿐) · `_workspace/51_quant_accumulation-radar.md` · `_workspace/52_audit_accumulation-lab.md`.
- 섞지 말 것: `auto-start-paper.bat`. `BACKLOG.md`·`_workspace/50_ops_market-day-1012.md`는 리더 문서 작업이라 별도 커밋. `logs/`는 gitignore(로컬 증거).

## 6. 쉬운 말
바뀐 건 전부 "옛날 기록으로 연습해 보는 프로그램" 안에서만 켜지는 부품이라, 곧 다시 켤 모의투자 앱의 규칙·안전장치·설정은 하나도 안 바뀌었다. 확인 시험 1,150개가 전부 통과했고 예전 연습 결과(돈치안 1,181번 거래 등)도 숫자 하나 안 틀리고 똑같이 나왔으며, 연습 중 증권사 서버에는 한 번도 접속하지 않았다. "매집 흔적 방식은 쓸 수는 있지만 지금 방식보다 낫지 않다"는 결론도 숫자와 맞다.
