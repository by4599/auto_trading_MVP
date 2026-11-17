# 21 — regime-sens 모드 구현 (지수 추세 MA 기간 민감도, §14.4)

## 결론
`--backtest.mode=regime-sens` 신설. regime-lab 승자 MA120의 ±20%(MA96·MA144)를 단일 파라미터로
스윕한다. regime-lab 및 기존 모드의 동작·출력은 불변. 컴파일·전체 테스트(408개) 통과.

## 바꾼 파일과 이유

### 1. `src/main/java/com/trading/backtest/BacktestOrchestrator.java`
- **`execute()`에 `regime-sens` 분기 추가** — regime-lab 분기 바로 뒤. `prepareCandidateUniverse("regime-sens", stressFrom, stressTo)` → `runRegimeSensitivity(...)`. regime-lab과 같은 창(stress-from/to)·후보 54종목.
- **`REGIME_SENS_PROFILES` 신설** (4개): `S0 필터OFF(회귀 앵커)`(false,200) / `S1 MA96 (-20%)`(true,96) / `S2 MA120 (기준)`(true,120) / `S3 MA144 (+20%)`(true,144). S0을 (false,200)으로 둔 이유: §14.4 G0가 (false,200)이라 값까지 동일해야 회귀 앵커로 완전 재현된다(비활성이라 maPeriod는 동작에 무영향이지만 앵커 동일성을 명시).
- **공유 헬퍼 3개 추출** — regime-lab과 sens가 함께 쓴다:
  - `applyRegimeFixedConditions()` — 진입 MA / 출구 P3 / 사이징 0.5R·동시5 세팅. runRegimeLab의 인라인 6줄을 그대로 옮긴 것(값 동일).
  - `restoreRegimeDefaults()` — indexTrend OFF·200, resetExitProfile, riskLimits 상수 복원. runRegimeLab의 인라인 복원 블록을 그대로 옮긴 것(값 동일).
  - `runRegimeProfile(rp, index, **total, tag**, ...)` — 로그 태그와 분모를 파라미터화. 나머지 로직(필터 세팅·walkForward·judge·logCrisisWindows) 동일.
  - `logCrisisWindows(rp, **tag**, result)` — 로그 태그만 파라미터화.
- **sens 전용 메서드 3개 신설**: `runRegimeSensitivity` / `logRegimeSensBanner` / `logRegimeSensSummary` (모두 `[RegimeSens]` 태그, 회귀 앵커 2개·§4 민감도 기준(PF≥1.15)·과최적화 경고를 로그로 남긴다).
- **가시성 변경**: `RegimeProfile` 레코드와 `REGIME_PROFILES`를 `private`→패키지 프라이빗(단위 테스트 introspection용). 값·동작 불변.

### 2. `src/main/java/com/trading/backtest/BacktestReportWriter.java`
- **`writeRegimeSensReport(...)` 신설** — `writeRegimeLabReport` 옆. 기존 `writeComparison`을 **수정 없이 그대로 호출**(title="Regime Sens…", prefix="REGIMESENS", `baselineOnPass=false`, `baselineSlug=null`). 판독 지침에 회귀 앵커 2개(S0=G0 1591·PF1.26·MDD33.5%, S2=G1 1192·PF1.51·MDD13.3%)·§4 민감도 기준·MDD 병행 확인·선견편향 차단·"합격해도 실전 아님"을 넣었다.

### 3. `src/test/java/com/trading/backtest/BacktestOrchestratorRegimeProfilesTest.java` (신규)
- 순수 단위 테스트(스프링·Mockito 없음). 정적 리스트만 검증.
  - `REGIME_SENS_PROFILES`가 정확히 [OFF, MA96, MA120, MA144]이고 96=120×0.8, 144=120×1.2.
  - `REGIME_PROFILES`가 여전히 [OFF, MA120, MA200] — 공유 리팩터가 regime-lab 앵커를 안 바꿨다는 회귀.

## regime-lab 불변을 어떻게 보장했나
- **공유 헬퍼는 regime-lab 프로필 리스트(REGIME_PROFILES)를 건드리지 않는다.** `runRegimeLab`은 여전히 `REGIME_PROFILES`를 넘기고, `runRegimeProfile`에 `tag="RegimeLab"`·`total=REGIME_PROFILES.size()(=3)`를 넘긴다. 로그 포맷은 `"[RegimeLab] (i/3) …"`로 이전과 **문자 그대로 동일**하게 재구성된다(태그·분모만 상수→인자로 치환, 값 동일).
- **세팅/복원 헬퍼는 인라인 코드를 값 변경 없이 이동**한 것뿐이다 — 실행 상태(진입 MA·P3·0.5R·동시5, 복원값 OFF/200·상수)가 동일하므로 walkForward 입력·judge 결과·리포트가 불변.
- **judgeData의 label/slug는 REGIME_LAB_LABEL/SLUG("MA+P3+RR1")를 그대로 공유** — judge()는 트레이드 수·PF·MDD로만 판정하고 label은 표기용이라 sens와 공유해도 무해.
- **`writeComparison`·`judge`·`writeBaseline`·`applyExitProfile`·`runRiskLab`·`IndexTrendRule`·`BacktestIndexRegimeSource`·`FilterProperties` 무수정.** writeRegimeSensReport는 writeComparison을 호출만 한다.
- **회귀 앵커 테스트**(`regimeLabProfilesUnchanged`)가 REGIME_PROFILES=[OFF,120,200]을 못박아, 향후 이 리스트가 바뀌면 테스트가 깨진다.
- 검증되지 않은 잔여: regime-lab의 **로그 텍스트** 자체는 실행하지 않았다(백테스트 실행 금지 규칙). 단, 포맷 문자열이 태그/분모 치환 외 동일함을 코드 대조로 확인.

## 실행 커맨드
```
.\gradlew.bat bootRun --args="--spring.profiles.active=backtest --backtest.mode=regime-sens"
```
창은 `stressFrom~stressTo`(2020-01-01~2026-07-21), 후보 54종목, 진입 MA·출구 P3·사이징 RR1 고정.

## 빌드/테스트 증거
```
[검증 증거]
명령: gradle test --console=plain --rerun   (TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build)
종료 코드: 0 (BUILD SUCCESSFUL in 18s)
결과: 408개 중 408개 통과, 실패 0, 에러 0  (기존 406 + 신규 2)
신규 클래스 단독: com.trading.backtest.BacktestOrchestratorRegimeProfilesTest → 2/2 통과
→ 판정: 통과
```

## 미해결 / 우려
- **파일 크기 초과**: `BacktestOrchestrator.java`가 764→**853줄**로 800줄 상한을 넘겼다. 공유 헬퍼 추출로 중복을 최소화했으나 sens 전용 배너/요약/드라이버 + 리포트 판독 지침으로 초과. 지시대로 랩별 클래스 분리 같은 과한 리팩터는 하지 않았다 — 별도 정리 작업으로 다룰 사안(범위 밖).
- **실행·판정은 strategy-quant 몫**(백테스트 실행 금지 규칙 준수). 회귀 앵커 재현 여부(S0=1591·S2=1192)는 실제 실행에서 확인해야 한다.
- 리포트 파일 슬러그는 regime-lab과 동일한 fileSlug("MA-P3-RR1")를 쓰되 파일 prefix는 `REGIMESENS`로 구분되어 파일명 충돌은 없다.
