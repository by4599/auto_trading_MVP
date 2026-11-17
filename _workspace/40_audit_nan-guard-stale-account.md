# 40_audit — NaN 파라미터 차단 · 낡은 잔고 매수 금지 감사

> 2026-10-10(토) · risk-auditor · 코드 수정 0 · gradle 미실행(지시 — 리더 결과 XML 905/905를 읽어 교차 확인) · 운영 H2 미접속.
> 리더가 에이전트 보고 요지를 이 경로에 보존했다. 리더 후속 조치는 맨 끝 §6.

## 0. 판정 — CRITICAL 0 · HIGH 0 · MEDIUM 0 · LOW 8 → 커밋 가능

조건: ① `git add`는 경로를 하나씩(미추적 `auto-start-paper.bat` 제외) ② 운영 반영은 재기동 필요(코드·yml·정적 파일 변경).

쉬운 말: "잔고를 못 받아 온 순간에는 새로 사지 않고, 파는 쪽은 그대로 둔다. 한도 칸에 '숫자 아님(NaN)'을 넣어 안전장치를 끄던
구멍을 막았다. 돈이 새는 새 길은 생기지 않았다."

| 이전 지적 | 상태 | 근거 |
|---|---|---|
| 37_audit M-2 "NaN"이 MDD 가드를 끔 | 해소 | `ParamCatalog.java:175`. NUMBER 파라미터 11개가 같은 분기, 기동 때 재검증도 같은 함수(`TradingParamService.java:53`) |
| 28_audit M-3 낡은 캐시로 매수 판정 | **paper에서만** 해소 | `application-paper.yml:61-62`. 기본값 false라 real 등 다른 프로필은 미해소 → real 전환 선행 조건 유지(G2가 먼저) |

## 1. 흐름 — 통과
RiskEngine 무수정(이력 2325b9d뿐) · `@Component` 자동 주입(`RiskEngine.java:24`) · 매수만 차단(`:34`) · 손절(`StopLossMonitor.java:176-177`,
낡으면 `:102-105`에서 원래 판정 생략) · 타임컷(`TimeCutScheduler.java:167-168`) · 최대보유(`MaxHoldScheduler.java:150-151`) 모두 Signal.sell →
영향 없음 · 강제청산은 RiskEngine 경로 밖 · 매수 진입점 두 곳(`TradingScheduler.java:110-111`, `DrillService.java:87-89`) 모두 새 룰을 탄다.

## 2. paper 실영향 — 장중에 거짓 '낡음'은 없다
- 낡음 생성 3곳: `KisPositionManager.java:66`(장외+보유 0 조회 생략 — 장중엔 `canSkipBalanceCall`이 false, `:112`), `:80`(조회 실패→캐시),
  `:183-184`(조회 실패+캐시 없음→DB 폴백). 3초 캐시는 신선 객체만 담고 `asStale()`은 새 객체라 캐시가 오염되지 않는다.
- 결론: 장중 '낡음' = "가장 최근 잔고 조회가 예외로 끝났다"(KIS I/O·rt_cd≠0·output2 없음, 또는 조회 함수 안 DB 예외). 28_audit 권고
  ("N초 이상 낡으면")보다 엄격(N=0) — 안전한 방향.
- 기동 직후 첫 조회 실패 → DB 폴백(낡음) → 첫 성공까지 매수 보류(의도). 덤으로 닫힌 구멍: 예전엔 보유 0 + DB 폴백이면 총자산 0으로
  `GlobalEquityStopRule.java:37`(current<=0 통과)과 DailyLoss를 지났고, 칸 사이징은 계좌 총자산을 안 써 매수가 나갈 수 있었다.
- 빈도 실측(장중 "잔고 API 실패" WARN): 09-22 74건/최장 111초 · 09-30 0건 · 10-01 54건/최장 78초 · 10-02 24건/최장 122초.
  10-01·02 실패 78건 중 72건이 I/O 타임아웃, 정각 직후(12·13·14·15시)에 몰림(원인 미상). 예상: 하루 수십 번, 대개 10초 안팎, 길면 약 2분 매수 보류.
- A동 영향: 돈치안 신호는 '수준형'(현재가 > 직전 20일 고가 · > MA120, 하루 1회 잠금 없음 — `DonchianBreakoutStrategy.java:79-84`)이라
  막혀도 다음 라운드로빈에 다시 나온다. 진입이 수 초~2분 늦어질 뿐 기회가 사라지지 않는다.
- 4인자 생성자 fresh 기본값 true(`Account.java:39-44`), 운영 호출부 3곳 모두 올바름. `TradingScheduler`가 넘기는 Account가 그대로 룰에 간다.
- SAFE_MODE 중엔 중복이지만 실익 3경우: ① 첫 실패~SAFE_MODE 진입(연속 3회 AND 90초) 사이 ② 자동 복귀 뒤에도 잔고만 계속 실패
  ③ HTTP 200 + rt_cd≠0 실패(`KisApiClient.java:319-324`가 성공으로 세어 SAFE_MODE가 안 걸림).

## 3. 백테스트 격리 — 3중
`@Profile("!backtest")` · `BacktestPositionManager.java:86`의 Account는 항상 신선 · `application-backtest.yml`에 키 없음(false).
ParamCatalog 변경은 NaN 거부뿐이고 백테스트는 app_setting을 안 읽는다 → 회귀 앵커 무영향(구조 판단).

## 4. 사유 조각·진단 화면 — 겹침 없음
"잔고 정보가 낡음"은 다른 조각·다른 룰 문구와 겹치지 않는다. 표시 흐름 `RiskBlockRecorder.java:78` → `diag-history.js:53` 등.

## 5. 비밀키·프로필·크기 — 위반 없음
비밀키 패턴 0건, `trading.risk.*`는 `trading.risk-block.*`와 별개 키, 키 표기 3곳 일치. Rule 37줄 · Resolver 59줄 · ParamCatalog 218줄.
NaN·무한대가 필요한 NUMBER 파라미터 없음(11개 모두 유한 min/max). 기동 재검증에서 비정상 저장값은 WARN 후 건너뛰고 코드 기본값 유지
(mddLimit이면 0.10) — 현재 운영 저장값 0.08은 새 검사를 통과한다. 범위 밖 참고: yml 바인딩 숫자(`trading.bucket-params.*` 등)는 이 검사를 안 거친다.

## LOW 8건

| # | 항목 | 내용 / 권고 |
|---|---|---|
| L-1 | 배선 테스트 부재 | `@Value` 키(`StaleAccountBuyGuardRule.java:28`)와 yml 키를 맞춰 보는 테스트가 없다 — `@Value` 쪽 오타면 테스트는 다 통과한 채 가드가 조용히 꺼진다. backtest 빈 부재도 테스트 없음. 권고: `IndexTrendWiringTest` 패턴 |
| L-2 | 거부 사유 귀속 순서(판정 보류) | 새 룰은 평가 순서 마지막(알파벳 순)이라, DB 폴백 때 MDD 룰이 낡은 숫자로 먼저 막으면 그 이름으로 기록된다(화면 "전체 손실이 한도에 가까워서"). 돈 영향 없음. `@Order`로 앞당기면 연속손실 1시간 잠금 시작이 늦어지는 부작용 |
| L-3 | 계속 낡을 때 가시성 | 잔고만 계속 실패하면 RUNNING인 채 매수 0건, 텔레그램 없음. 권고(BACKLOG): "장중 N분 이상 연속 낡음" 알림 |
| L-4 | 기본값이 열린 쪽 | 스위치 기본 false, 4인자 생성자 fresh 기본 true — 새 PositionManager가 폴백에서 `asStale()`을 빠뜨리면 무력화. real 전환 체크리스트에 |
| L-5 | 사유 문구 부정확 경로 | 장외+보유 0 생략은 실패가 아닌데 "(조회 실패)"가 붙는다 — 실제로는 수동 리허설 매수에서만 보인다(표시 문제) |
| L-6 | 예약 리허설 매수가 막힐 수 있음 | buy-if-flat이 낡음에 막히면 그날 재시도 없이 대기(텔레그램으로 보임). drill은 꺼져 있다 |
| L-7 | 문서·주석 드리프트 | CLAUDE.md 룰 표 15줄 vs 코드 16개, `:286` "매수 차단 룰은 낡으면 보수적 차단"은 스위치 켠 paper에서만 참, 주석의 "14개" 낡음 |
| L-8 | 칸 OFF일 때 사이징 시점 | 판정 뒤 사이징이 스냅샷을 다시 읽는다 — paper(칸 ON)는 무해, 칸 OFF(real 등)는 낡은 총자산으로 수량이 나올 수 있다 → real 전환 확인 항목 |

재기동 후 확인: ① 기동 로그 "Loaded 16 risk rules … StaleAccountBuyGuardRule" ② `/api/params` risk.mddLimit "0.08"
③ 잔고 실패가 난 날 `/api/risk/blocks`에 StaleAccountBuyGuardRule(실패 없으면 0건 정상).

감사하지 못한 것: 빌드·테스트·백테스트 미실행(지시) · 스프링 실기동 미확인(16번째 순서는 추정) · 새 룰의 실제 차단 사례 미관측 ·
런처(.bat) 미열람 · 운영 H2 미접속 · 정적 화면 미표시 · 정각 직후 실패 집중 원인.

## 6. 리더 후속 조치
- L-1: `src/test/java/com/trading/risk/StaleAccountBuyGuardWiringTest.java` 추가 — 실제 paper 설정으로 컨테이너를 띄워 낡은 스냅샷 매수
  거부를 확인하고, backtest에서는 빈이 없음을 확인한다. 실행 결과는 ③ 구현이 끝난 뒤 전체 테스트와 함께 기록(그 시점 작업 트리에
  ③의 Red 단계 테스트가 있어 컴파일이 막혔다).
- L-7: CLAUDE.md 룰 표·문구는 ①~⑤를 모두 마친 뒤 한 번에 갱신한다.
- L-2·L-3·L-4·L-5·L-8: 이번 범위 밖 — 기록으로 남긴다(L-4·L-8은 real 전환 확인 항목).
