# 35_audit — paper 공시 쿨다운 OFF(설정 1줄) 감사

> 2026-10-10(토) 00:15~00:33 KST · risk-auditor · 코드·파일 수정 0건 · gradle 미실행(지시대로) · 운영 H2 DB는 열지 않음(지시대로).
> 실행 중인 앱에는 **읽기 전용 GET 3회**만 보냈다: `/api/params`, `/api/risk/blocks?days=30`, `?days=10`. 보내기 전에 두 컨트롤러가 빈 값과 DB만 읽고 KIS를 부르지 않는지 코드로 확인했다(`TradingParamService.java:67-90`, `RiskBlockController.java:58-78`).
> 리더가 에이전트 보고 전문을 이 경로에 보존했다(감사자는 읽기 전용). 리더 후속 조치는 맨 끝 §4.

## 0. 판정 — CRITICAL 0 · HIGH 0 · MEDIUM 0 · LOW 5 → **재시작 적용 가능**

조건 2개:
1. 재기동은 **bootRun 경로(`run-paper.bat`)로** 한다. 운영 빌드 폴더에 복사된 yml은 아직 `enabled: true`다(`C:/Users/SAMSUNG/auto_trading-build/resources/main/application-paper.yml:47`). bootRun의 processResources가 이 사본을 덮어써야 변경이 반영된다.
2. 재시작 후 `GET /api/params`에서 `filters.disclosureCooldown.enabled`의 value가 **"false"**인 것을 보기 전에는 "적용 완료"나 "L-5 해소"라고 쓰지 않는다(L-1).

**쉬운 말로**: "공시가 난 종목은 5일 동안 사지 마" 스위치를 끄는 일이다. 이 스위치는 원래 **살 때만** 보고 **팔 때는 보지 않았다**. 그래서 꺼도 손절·타임컷·강제청산은 하나도 바뀌지 않는다. 걱정은 하나뿐이다: 설정 화면에 저장된 값이 있으면 그게 yml을 이긴다. 지금까지 남은 기록으로는 저장된 값이 없고, 재시작 후 한 번 확인하면 확정된다.

- 변경 범위(`git diff`): `application-paper.yml` 46-50줄, `PaperTrendSleeveConfigTest.java` 41줄과 122-128줄. `src/main/java` 변경은 0건.
- **재시작으로 실제로 바뀌는 건 이 yml 한 줄뿐이다.** 실행 중인 빌드(10-01 12:55) 이후 `src/main`에서 수정 시각이 바뀐 파일은 `application-paper.yml` 하나다(`find -newermt`). 운영 빌드 폴더의 클래스 277개를 소스와 대조했더니 소스 없는 클래스는 0개였다. 10-01 09:05처럼 같은 빈이 두 개가 되어 기동이 실패할 요인은 없다.
- 이전 감사와의 연결: `30_audit` **L-5가 이 변경으로 해소될 대상이다(재시작 확인 후 "해소"로 표기)**. 30_audit H-1은 `32_audit`에서 "코드상 해소, 배포 후 확인 조건부"로 기록돼 있다. 이번 범위 밖이라 다시 확인하지 않았다.

## 1. 발견 사항

| 심각도 | 항목 | 위치 | 내용 | 근거 |
|---|---|---|---|---|
| LOW | L-1 저장값이 없다는 건 강한 정황이지 증명이 아니다 | `application-paper.yml:120`(`AUTO_SERVER=TRUE`), `TradingParamService.java:44-65` | 앱 안에서 `app_setting`에 쓰는 경로는 전부 로그를 남기는데, 그 로그가 없다. 하지만 AUTO_SERVER 때문에 앱 밖 프로세스(H2 Shell 등)가 붙어서 쓴 행은 로그가 남지 않는다. 확정 수단은 재시작 후 `/api/params` 하나뿐이다 | §2-② |
| LOW | L-2 테스트 로그가 운영 로그에 섞인다(기존 문제). 재시작 판독을 흐릴 수 있다 | `logs/paper-2026-09-30.0.log.gz:1018-1025, 1996-2003` | 테스트 스레드(`[Test worker]`)가 운영 로그에 `[Param] filters.disclosureCooldown.enabled : false → true`, `risk.mddLimit : 0.1 → 0.12`, `저장된 투자 파라미터 1건 적용 완료`를 남겼다. 목(mock) 리포지토리를 쓰는 단위테스트(`TradingParamServiceTest.java:32-37`)라 DB에는 쓰지 않았다. 그래도 `[Param]`만 grep하면 "저장값이 있다"로 잘못 읽힌다. 이번 리더 테스트(00:09) 분은 0줄이다(로그를 분리한 것으로 보임) | paper 프로필 `@DataJpaTest`가 테스트 JVM의 로그 파일을 `logs/paper.log`로 설정한다(`application-paper.yml:132-134`) |
| LOW | L-3 주석·테스트명의 "필터를 전부 끄고 채점"은 부정확하다 | `application-paper.yml:46`, `PaperTrendSleeveConfigTest.java:123` | D0는 `allFiltersOff()` 뒤에 **트레일링(P3)과 지수 MA120을 다시 켰다**(`ExecutionKnobs.java:56-60`, `DonchianLab.java:90`). "D0와 맞추려면 지수 필터도 꺼야 한다"고 오독될 여지가 있다. 바로 아래 51-52줄 주석이 막아 주긴 한다. "공시 쿨다운을 끈 채 채점"이 정확한 표현이다 | §2-③ |
| LOW | L-4 문서가 재시작·확인 전에 결과를 먼저 적었다 | `Claude.md` 리스크 룰 표의 `DisclosureCooldownRule` 행(작업 트리, 감사 도중 추가됨) | 표에는 "⏸ 꺼짐 … L-5 해소"라고 적혀 있다. 그런데 실행 중인 앱은 지금도 ON이다. `/api/params` value "true"를 00:2x에 직접 확인했다 | 증거 기반 완료 원칙 |
| LOW | L-5 주말 재기동 경로 | `auto-start-paper.bat:34,38,45` | 토·일에는 기동을 건너뛴다. 앱을 끄고 작업 스케줄러에 맡기면 월요일 08:30까지 꺼진 채로 있다. `run-paper.bat`(46줄, bootRun)을 직접 실행해야 한다 | — |

참고 사항(위 집계에서 제외):
- I-1 차단 통계의 구성이 바뀐다. `RiskEngine`은 첫 거부에서 멈추고(`RiskEngine.java:31-34`), 쿨다운은 룰 15개 중 4번째다(`paper-2026-10-01.0.log.gz:1556`). 끄면 같은 신호를 뒤쪽 룰(지수 추세, 최대 보유 등)이 대신 거부한다. 그래서 `/api/risk/blocks`의 룰별 집계가 달라진다. 새로 풀리는 매수는 "쿨다운 **하나만** 막던 매수"뿐이다.
- I-2 재시작 직후에는 지수 판정이 비어 있다. 판정(`lastSuccess`)은 메모리 필드이고(`KisIndexRegimeSource.java:82`) 조회는 거래일에만 한다(`:150`). 다음 거래일 첫 조회 전까지 `IndexTrendDataGateRule`이 매수를 막는다(의도된 동작: 모르면 막기). 32_audit M-A(전날 판정이 개장 직후 매수를 통과시키는 틈)는 그날은 오히려 닫힌다.

## 2. 항목별 근거

### ① 스위치를 읽는 모든 경로 — 쿨다운만 막던 매수가 풀리는 것 외에는 바뀌는 게 없다. 매도·청산 무영향

| 읽는 곳 | OFF일 때 | 매매 영향 |
|---|---|---|
| `DisclosureCooldownRule.java:46-48` | 매도는 46줄에서 **스위치를 보기도 전에** 통과한다(원래부터). 매수는 48줄에서 바로 통과하고, 56-57줄의 H2 공시 조회를 건너뛴다 | 쿨다운만 막던 매수가 통과. 그 외 없음 |
| `ReviewService.java:158-176` | `{"enabled":false}`만 넣고 162-175줄(DB 조회, `active/until`)을 건너뛴다 | 화면 표시 전용, KIS 호출 없음 |
| `tab-review.js:61-68, 184-186` | 배지가 안 뜨고(`cooldown?.active` 없음) 상세에 "OFF"가 표시된다 | 표시 전용, 오류 경로 없음 |
| `diag-history.js:48` | 라벨표일 뿐. 과거 기록(09-22 등)은 그대로 보인다 | 없음 |
| `ParamCatalog.java:117-125` | `/api/params` value가 "false"가 된다(기본값 "false"와 같음, 118줄) | 없음. 화면에서 다시 켜면 DB에 저장돼 yml을 이긴다(설계대로) |
| `RiskRuleNameResolver.java:41` | "공시 쿨다운" 사유가 새로 안 생길 뿐이다 | 없음(집계 라벨) |
| `ExecutionKnobs.java:45-46`, `FullBacktestLab.java:110-121` | `@Profile("backtest")`(`ExecutionKnobs.java:16`, `FullBacktestLab.java:22`)라 paper 컨텍스트에는 빈이 없다 | 없음 |

- `FilterProperties`를 쓰는 나머지 8곳(`BucketParameterResolver`, `BucketParameters`, `IndexTrendRule`, `IndexTrendDataGateRule`, `KisIndexRegimeSource`, `EntryTimeWindowRule`, `IndexRegimeRule`, `VolatilityBreakoutStrategy`)은 쿨다운 값을 읽지 않는다. main에서 `getDisclosureCooldown`을 부르는 곳을 전부 찾아보니 위 표에 있는 곳이 전부였다.
- 손절·타임컷·최대보유·`RiskMonitor`·`LiquidationService`는 이 스위치를 참조하지 않는다(같은 검색). DART 공시 수집도 스위치와 무관해서 B-4 표본은 계속 쌓인다.
- 실측: 최근 30일 차단 횟수 2위가 쿨다운이었다(160회, 57행, 6종목). 하지만 **마지막 기록이 09-22 15:24의 B동 스캘핑**이고, A동 전환(10-01) 이후 차단 기록은 0행이다. 마지막 지수 판정(10-02 09:02, `paper-2026-10-02.0.log.gz`)은 KOSPI 6971.35 < MA120 7157.90(−2.61%)이었다. 지수 필터가 모든 신규 매수를 막는 동안에는 이 변경의 실제 효과가 0이다.

### ② 저장값(`app_setting`) 덮어쓰기 — 없다고 봐도 된다(강한 정황). 확정은 재시작 후

관측 평가:
- **`/api/params` value "true"로는 판별할 수 없다.** yml이 true였으니 DB 행이 없어도 true가 나온다. 이것만으로는 아무 결론도 못 낸다. (00:2x 재확인: value true, default false, 일수 5.)
- **12:55 기동 로그에 `[Param]` 줄이 없다는 건 유효한 증거다.** 아래 전제 4개를 모두 확인했다.
  1. `loadOnStartup`이 실제로 돌았다. 같은 `ApplicationReadyEvent` 리스너인 `ShadowPortfolioReconciler.onStartup()`(117줄, 122줄)이 12:55:23에 `기동 재동기화 완료`를 남겼다(`paper-2026-10-01.0.log.gz:1570`). ready 리스너에서 예외가 나면 앱이 죽는데, 앱은 살아 있다.
  2. INFO 로그가 찍히는 설정이다(`application.yml:117`, `com.trading: INFO`).
  3. 적용한 행이 1건이라도 있으면 INFO를 남기고(`TradingParamService.java:62-64`), 모르는 키나 잘못된 값이면 WARN을 남긴다(50줄, 55줄). 10-01 로그에는 `[Param]`이 **한 줄도 없다.** 그러니 그 시점 `app_setting`은 비어 있었다(유효한 행도 무효한 행도 0).
  4. 지금 실행 중인 앱이 바로 그 기동이다. PID 3376, 생성 2026-10-01 12:55:00, 인자는 `--spring.profiles.active=paper` 하나다(Win32_Process 조회). 그 뒤 재시작은 없었다.
- 그 뒤에 행이 생겼을 가능성:
  - 앱 안에서 쓰는 주체는 `TradingParamService` 하나뿐이다(`AppSettingRepository` 사용처 전수 검색). 저장하면 키마다 INFO를 남기고(125줄), 원복하면 INFO를 남긴다(136줄). 10-01 12:55부터 10-09 23:57까지 운영 프로세스의 `[Param]` 줄은 0건이다. 설정 화면은 바뀐 키만 보내므로(`core.js:349-360`) 다른 값을 저장해도 이 키는 들어가지 않는다.
  - 10-05 03:02:45부터 10-09 21:04:50까지 로그가 비어 있다. 프로세스 생성 시각이 그대로라 재시작은 아니었다. 리더 기록(34_ops §4, Kernel-Power 506/507)대로 대기 모드였다면 요청을 처리할 수 없었다.
  - **남는 구멍(L-1)**: `AUTO_SERVER=TRUE`라서 앱 밖 프로세스가 SQL로 쓴 행은 로그가 없다. H2 웹 콘솔은 꺼져 있다(설정 없음). paper 프로필 `@DataJpaTest` 5개는 임베디드 DB로 바뀌므로(이를 되돌리는 `@AutoConfigureTestDatabase` 설정이 없음) 테스트가 쓰는 경로는 아니다.
- yml 말고 이 키를 덮을 수 있는 다른 경로 — **찾지 못했다:**
  - 기동 인자: 프로필 하나뿐이다(PID 3376 커맨드라인).
  - 외부 설정 파일: 기동할 때의 작업 폴더가 저장소 루트다(`paper-2026-10-01.0.log.gz:1536`, "started by SAMSUNG in …\auto_trading"). 스프링 기본 규칙상 루트의 낡은 `application-paper.yml`도 `file:./`로 같이 로드되지만, 그 파일에는 `trading.filters` 키가 없다(1-23줄). `config/` 폴더와 `application-paper-local.yml`은 없고, yml 어디에도 `spring.profiles.include/group`이나 `config.import`가 없다. 저장소 안의 `build/`, `build-paper/`, `build-bt/` 사본은 실행 클래스패스가 아니다(빌드 폴더는 `build.gradle:15-16`에 따라 `C:/Users/SAMSUNG/auto_trading-build`).
  - 환경변수: 이 셸에는 `TRADING_*` 중 `TRADING_BUILD_DIR`만 있고 `SPRING_*`·`JAVA_TOOL_OPTIONS`는 없다. 실행 중인 앱의 환경은 직접 보지 못했다(§3).
- **재시작 후 확정하는 순서:**
  1. 새 PID의 `[main]` 줄에서 `Started TradingApplication`과 `[Reconciler] 기동 재동기화 완료`를 확인한다(ready 리스너가 끝났다는 뜻). 이 줄 전에 `/api/params`를 보면 저장값이 아직 적용되기 전일 수 있다.
  2. `GET /api/params`에서 `filters.disclosureCooldown.enabled` value가 **"false"**인지 본다. 이 값은 룰이 읽는 바로 그 객체에서 나온다. `ParamCatalog.java:120`이 읽는 값과 `DisclosureCooldownRule.java:47-48`이 읽는 값이 같은 객체다(`@ConfigurationPropertiesScan`, `TradingApplication.java:20`, 다른 `FilterProperties` 빈 없음). **이것이 유일한 확정 증거다.**
  3. 보조 확인: 새 PID의 `[main]` 줄에 `[Param] 저장된 투자 파라미터`가 없어야 한다. grep할 때 PID와 `[main]`으로 걸러야 한다(L-2). 이 줄은 건수만 찍고 키 이름은 안 찍으므로, 1건 이상이 나오면 2번으로 판정한다.
  4. `[RiskEngine] Loaded 15 risk rules`에 `DisclosureCooldownRule`이 그대로 들어 있어야 한다(룰은 남고 통과만 한다). 검토종목 탭에 "공시 쿨다운 OFF"가 보여야 하고, 다음 거래일 `/api/risk/blocks`에 새 `DisclosureCooldownRule` 행이 없어야 한다.
- 2번에서 "true"가 나오면(= DB 행이 있다는 뜻): **"기본값 원복" 버튼으로 지우지 말 것.** `resetAll()`은 19개 파라미터 전부를 런타임에 코드 기본값으로 되돌려서 전역 트레일링을 꺼 버린다(`ParamCatalog.java:97-110`의 기본값 false/0.03/0.02 대 `application-paper.yml:43-45`의 true/0.01). 칸이 없는(=VB) 보정 포지션이 바로 이 전역값을 쓴다(`BucketParameterResolver.java:12,42-44`, 30_audit L-7). 방법은 둘이다.
  - 쿨다운 키 하나만 `PUT /api/params {"filters.disclosureCooldown.enabled":"false"}`로 고친다. 이 경우 DB에 false 행이 남아 앞으로의 yml 변경을 이긴다는 점을 기록해 둔다.
  - 원복한 직후 한 번 더 재시작해서 yml 값으로 되돌린다.

### ③ D0는 쿨다운 OFF로 채점됐다 — 맞다(실행 당시 커밋 기준 정적 추적)

- §15.7 D0는 `--backtest.mode=donchian-sens-sz2`다(`CandidateLabRouter.java:189-191`). 경로는 `DonchianLab.runSensitivity(SIZING_SZ2)`(94-124줄) → `applyFixedWithIndexFilter`(86-91줄)이고, 순서는 이렇다.
  1. `enableDonchianOnlyResetRsi`: 전략 스위치만 바꾼다(`StrategyToggles.java:89-95`).
  2. `applyP3ExitWithHalfRisk` → `applyExitProfile(P3)` → **`allFiltersOff()`에서 쿨다운을 끈다**(`ExecutionKnobs.java:55` → 45줄). 그다음 트레일링만 다시 켠다(56-60줄).
  3. 사이징 0.25R·동시 5종목(`DonchianLab.java:89`).
  4. 지수 MA120 ON(90줄).
  그 뒤 루프는 돈치안 파라미터만 바꾸고(106-107줄), `LabExecutor.evaluate`는 Walk-Forward만 돌린다(`LabExecutor.java:35-42`). 백테스트 패키지에서 쿨다운을 켜는 코드는 `FullBacktestLab.java:110-121`뿐이다.
- **실행 당시 코드로 봐도 같다.** D0 리포트는 `logs/backtest/REPORT-RISKLAB-DONCHIAN-P3-SZ2-IDX120-SENS-20260804-2049.md`다(실행 2026-08-04 20:49:45, D0 1181건·PF 1.92·+1.340%·MDD 4.2%로 `BACKTEST-DESIGN.md:864`와 일치). 그 모드를 만든 커밋 `5d8f1fa`(20:40:28)의 `BacktestOrchestrator.java`를 따라가면 `donchian-sens-sz2`(311-314줄) → `applyDonchianFixedWithIndexFilter`(1057줄) → `applyRegimeExitSizing`(789-790줄) → `applyExitProfile`(630-634줄) → `allFiltersOff()`(431-438줄, 437줄에서 쿨다운 false)다. 그 리비전에서 백테스트 패키지 안의 쿨다운 참조는 이 파일의 8곳뿐이고, 켜는 곳(382-394줄)은 full 모드 필터 A/B다. 08-17 분리 리팩터(`bc8d200`, 커밋 메시지 "동작 불변")가 이 코드를 지금의 `ExecutionKnobs`/`DonchianLab`으로 옮겼다.
- `FullBacktestLab`은 **다른 랩이다.** B-3 `full` 모드(VB)이고, 다른 모드가 하나도 맞지 않을 때만 실행된다(`BacktestModeRunner.java:76-80`). 변형마다 `allFiltersOff()`를 한 뒤 필요한 필터를 켜고(`FullBacktestLab.java:126-129`), 끝나면 다시 끈다(76줄). §9 채택 결과(`BACKTEST-DESIGN.md:223-235`)가 여기서 나왔다. 한 JVM은 모드 하나만 돌고 종료하므로(`BacktestOrchestrator.java:43`) 이 상태가 D0 실행으로 넘어갈 수 없다.
- backtest-db의 `app_setting`도 끼어들 수 없다. 백테스트는 `CommandLineRunner`로 main 스레드에서 돌고(`BacktestOrchestrator.java:19,33-43`), 끝나면 `System.exit`한다. 그래서 `ApplicationReadyEvent`가 발행되지 않고 `loadOnStartup`이 아예 돌지 않는다.
- 한계: 리포트에는 필터 상태가 찍히지 않는다. "OFF"는 실행 기록이 아니라 그 시점 커밋을 정적으로 추적한 결과다. 20:40~20:49 사이의 미커밋 작업 트리는 확인할 수 없다(그 사이 커밋 `8528d35`는 market 패키지 수정).

### ④ 아키텍처 규칙 — 전부 지켜진다

- 규칙 1·2(흐름): `src/main/java` 변경 0건. 쿨다운 룰은 계속 `RiskEngine`에 주입된 채로(15개, `paper-2026-10-01.0.log.gz:1556`) 통과만 한다.
- 규칙 3: `RiskEngine` 무수정. 마지막 변경이 초기 커밋 `2325b9d`다.
- 규칙 5(청산 상태머신): 무관하다. 매도는 원래부터 이 룰을 통과했다(`DisclosureCooldownRule.java:46`).
- 규칙 6(A동은 검증된 조합대로만): **위반이 아니라 오히려 검증 조합에 맞추는 변경이다.** 검증 조합 D0가 쿨다운 OFF였고(③), 검증된 파라미터·자금 비율(400만원)·낙폭 한도는 건드리지 않았다.
- 프로필 격리: backtest는 `application.yml`과 `application-backtest.yml`만 읽는다. yml 어디에도 profile include/group이 없고, 두 파일에는 `trading.filters`가 없다. main 코드는 paper yml을 읽지 않는다(언급은 Javadoc뿐). 랩은 필터를 직접 세팅한다. `trading.bucket.enabled`와 텔레그램 격리는 그대로다. 실계좌 노출 없음(`application-paper.yml:7` 모의 도메인 그대로). diff에 비밀키 없음.

### ⑤ 옛 값(true)을 전제하는 테스트 — 없다

- 실제 paper yml을 읽는 테스트는 `PaperProfileYaml`(클래스패스의 `application-paper.yml`, `PaperProfileYaml.java:29-32`)을 쓰는 두 개다.
  - `PaperTrendSleeveConfigTest`: 쿨다운을 단언하는 건 새 테스트(122-128줄)뿐이다.
  - `TrendSleeveExitTest`: 필터는 트레일링용 `BucketParameterResolver`에만 쓰고 `RiskEngine(List.of())`로 룰 없이 돈다(`TrendSleeveExitTest.java:75-76,114,133`).
- paper 프로필 `@DataJpaTest` 5개(FillStateConcurrency, FillStateUpdater, OrderFilledEvent, OrderHistoryQuery, DiagnosticsQuery)는 쿨다운 룰·`RiskEngine`·`ReviewService`를 import하지 않는다. `IndexTrendWiringTest`는 자기 `FilterProperties`를 따로 등록한다(35줄, 69줄).
- 쿨다운을 true로 쓰는 테스트는 전부 새 객체에 직접 세팅한다: `DisclosureCooldownRuleTest.java:73,85,97,112`, `ReviewServiceTest.java:140`, `TradingParamServiceTest.java:113-119`(목 리포지토리).
- 실행 결과(Red 1건 실패 → Green 890/890)는 리더 보고이고, 나는 재현하지 않았다(지시대로).

### ⑥ B동을 다시 켤 때의 함의 (한 줄)

쿨다운은 칸을 구분하지 않는 전역 스위치(`DisclosureCooldownRule.java:45-62`에 칸 판정 없음)라서, B동을 다시 켜면 B-3에서 "트레일링 1% + 쿨다운 5일" 조합으로 채택한 손실 축소(§9, PF 0.66→0.81, MDD 49.2→27.9%) 중 쿨다운이 빠진 채로 돌고, 쿨다운을 다시 켜면 A동에도 걸려 D0와 또 어긋나므로, B동을 재가동할 때는 칸별 쿨다운(코드 변경)이나 재검증이 함께 필요하다.

## 3. 감사하지 못한 것

- gradle 빌드·테스트(지시대로). 890/890과 Red-Green은 리더 보고다.
- 운영 H2의 `app_setting` 직접 조회(지시대로). 행이 없다는 건 로그로만 추정했다(L-1).
- 실행 중인 앱(PID 3376)의 환경변수. 이 셸의 것만 봤다. 재시작하는 셸의 환경이 새 앱에 그대로 들어간다.
- 재시작 자체와 그 뒤 동작(주말 KIS 응답, 재동기화 결과, SAFE_MODE 여부)은 관측하지 못했다.
- 보유 0은 리더 보고에 의존했다. 장외에 KIS를 부를 수 있는 조회 API는 일부러 피했다.
- 루트 `application-paper.yml`이 `file:./`로 로드된다는 건 스프링 부트 기본 규칙에 근거한 판단이고, 실행 증거는 보지 않았다(이 키와는 무관).
- 10-05~10-09 대기 모드: 로그 공백과 프로세스 생성 시각만 확인했다. Windows 이벤트 로그는 34_ops 기록에 의존했다.
- D0의 쿨다운 OFF는 커밋 기준 정적 추적이다(리포트에 필터 상태가 기록되지 않음).
- 쿨다운 외의 paper↔D0 차이 13개(29_impl §5)는 다시 감사하지 않았다.
- 내 작업 흔적: 스크래치 폴더(저장소 밖)에 옛 리비전 소스 사본 1개와 PowerShell 스크립트 1개를 만들었다. 셸 `ls`·`ps` 명령 2개가 응답 없이 백그라운드로 넘어갔다(읽기 전용이라 무해하지만 아직 남아 있을 수 있다).

## 4. 리더 후속 조치 (2026-10-10)

| 지적 | 조치 |
|---|---|
| L-3 문구 | yml 주석·테스트 표시명·34_ops §0을 "공시 쿨다운을 끈 채 채점(allFiltersOff 뒤 트레일링·지수 MA120만 다시 켬)"으로 고침. 고친 뒤 전체 테스트 재실행 890/890(34_ops §3) |
| L-4 · 조건 2 | `CLAUDE.md` 표 행에서 "L-5 해소" 표기를 빼고 "확인은 34_ops §5"로 바꿈. 앱 기동 후 `/api/params` value "false"를 본 다음에만 해소로 적는다 |
| L-5 · 조건 1 | 기동기 `start-paper-if-needed.ps1:30-33`도 주말에 기동을 건너뛴다(작업 스케줄러가 실행하는 것은 이 .ps1). 리더 셸에서는 `run-paper` 기동기를 실행할 수 없어(34_ops §5) **앱 정지까지만** 했다. 기동은 사용자가 `run-paper`를 직접 실행하거나 10-12(월) 08:30 자동 기동 — 둘 다 bootRun 경로라 조건 1 충족 |
| L-1 · L-2 | 기동 후 34_ops §5의 확인 순서(새 PID `[main]` 줄로 거른 뒤 `/api/params`)대로 확정 |
