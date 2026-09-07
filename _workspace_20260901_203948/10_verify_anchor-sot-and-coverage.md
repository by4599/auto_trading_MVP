# 10_verify_anchor-sot-and-coverage — 독립 증거 검증

검증일: 2026-08-17 21:20~21:35 KST · 담당: 리더 직접 실행
대상: `_workspace/8_impl_anchor-sot-and-coverage.md`의 변경 전량 (미커밋)
브랜치: `backtest/regime-filter-and-validation` (HEAD `62f6976`)

> 경위: `paper-ops-verifier` 서브에이전트가 세션 사용 한도로 조기 종료(증거 0건)해, 한도 해제 후
> 리더가 직접 재실행했다. **아래 수치는 전부 이 세션에서 직접 실행한 명령의 출력이며,
> 구현자 보고를 재인용하지 않았다.**

## 판정 요약

| # | 주장 | 결과 | 판정 |
|---|---|---|---|
| 1 | 테스트 543/543 통과 | 82클래스 543개 중 543개 통과, 실패 0·에러 0·스킵 0, 종료 코드 0 | ✅ |
| 2 | risk-lab이 오늘 값을 원 단위 재현 | 5개 프로필 전 지표 일치 (아래 표) | ✅ |
| 3 | 기준선 yml 불변 | md5 `c2d8460d4874b99c4eaecc5e76d0b800` — 4회 실행 전후 동일 | ✅ |
| 4 | 앵커 대조 절이 리포트에 찍힌다 | `## 회귀 앵커 대조` 절 출력 확인 (일치·불일치 **양쪽** 확인) | ✅ |
| 5 | 커버리지 관문이 strict에서 채점 전 중단 | 종료 코드 1, `IllegalStateException`, 채점 미진입 | ✅ |
| 6 | 휴장일을 결측으로 오판하지 않는다 | 주말·성탄절(12/25)·연말휴장(12/31) 정확히 제외 | ✅ |
| 7 | 스프링 배선 정상 | 컨텍스트 3.608초 기동, 빈 생성 오류 0건 | ✅ (단서 아래) |
| 8 | 라이브 매매 경로 무수정 | `git status` 기준 이번 트랙 변경은 전부 `com.trading.backtest` | ✅ |

**이번 변경에 대한 실패·미검증 항목 0건.**

---

## 1. 테스트 (강제 전량 재실행)

```
명령: TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build gradle test --rerun-tasks --console=plain
종료 코드: 0
결과: BUILD SUCCESSFUL in 23s · 4 actionable tasks: 4 executed (UP-TO-DATE 없음)
      XML 집계: 82개 클래스 / 543개 중 543개 통과, 실패 0 · 에러 0 · 스킵 0
신규·보강 테스트 클래스:
  BaselineStoreTest 6 / BaselineDriftReporterTest 7 / CandleCoverageCheckerTest 6
  CandleBackfillServiceTest 14 / BacktestDataPropertiesTest 17   (전부 실패 0)
→ 판정: 통과 (직전 기준 519 → 543, +24)
```

## 2. 재현성 — 이 작업의 핵심 (원 단위 대조)

```
명령: gradle bootRun --args="--spring.profiles.active=backtest --backtest.mode=risk-lab
                             --backtest.write-baseline=false"
종료 코드: 0
```

| 프로필 | 변경 전 (오늘 06:27 기록 실행) | 변경 후 (21:22 검증 실행) | 일치 |
|---|---|---|---|
| RR0 1.0R·동시5 | 666 · PF1.53 · 0.933% · MDD20.96% · 128.39% · 22,838,900 | 동일 | ✅ |
| RR1 0.5R·동시5 | **781 · PF1.96 · 1.296% · MDD9.87% · 124.28% · 22,427,654** | 동일 | ✅ |
| RR2 0.5R·동시3 | 508 · PF1.92 · 1.156% · MDD6.63% · 62.15% · 16,214,652 | 동일 | ✅ |
| RR3 0.25R·동시5 | 732 · PF1.83 · 1.107% · MDD4.63% · 38.39% · 13,839,334 | 동일 | ✅ |
| RR4 0.5R·동시2 | 322 · PF1.53 · 0.933% · MDD8.58% · 22.14% · 12,214,431 | 동일 | ✅ |

리포트 대조: 직전 리포트와 diff = **"실행 시각" 1줄뿐**(지표 diff 0줄).
→ **판정: 통과. 재현성을 고치는 변경이 재현성을 깨지 않았다.**

## 3. 앵커 대조 — 일치·불일치 양쪽 확인

### 3-1. 일치 케이스 (정상 실행)

```
## 회귀 앵커 대조 — docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml
- ✅ 앵커와 일치 (대조 프로필: RR1 하프(0.5R·동시5))
| 트레이드 781→781 ✅ | PF 1.959→1.959 ✅ | 기대값 0.0130→0.0130 ✅ | MDD 0.0987→0.0987 ✅ | 승률 0.5186→0.5186 ✅ |
```

### 3-2. 불일치 케이스 (일부러 어긋나게 만들어 확인 — 구현자가 안 한 검사)

기준선 yml을 백업한 뒤 `trades: 781→779`, `max-drawdown: 0.0987→0.1100`으로 임시 변조하고 재실행:

```
## 회귀 앵커 대조 — docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml
- ⚠ **앵커 드리프트 2건** (대조 프로필: RR1) — 트레이드 779→781, MDD 0.1100→0.0987
| 트레이드 779→781 ⚠ 다름 | PF ✅ | 기대값 ✅ | MDD 0.1100→0.0987 ⚠ 다름 | 승률 ✅ |
종료 코드: 0  ← 드리프트로 실행을 실패시키지 않는다(설계대로)
```

**복구 확인**: `md5 c2d8460d4874b99c4eaecc5e76d0b800` 원값 일치 +
`git status --porcelain -- docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml` **출력 없음**(커밋 상태와 동일).
→ 판정: 통과. 변조는 남아 있지 않다.

## 4. 커버리지 관문 (strict 차단)

```
명령: gradle bootRun --args="--spring.profiles.active=backtest --backtest.mode=cost-lab
                             --backtest.candidate-to=2026-12-31 --backtest.write-baseline=true"
종료 코드: 1
예외: IllegalStateException: [Coverage] cost-lab — 판정 창 끝(2026-12-31)까지 캔들 부족
      — 마지막 캔들 없음(최근 14일), 결측 거래일 9일
      [12-17, 12-18, 12-21, 12-22, 12-23, 12-24, 12-28, 12-29, 12-30]
      ... write-baseline=true 실행이므로 중단한다. 백필로 채운 뒤 재실행할 것.
      at CandleCoverageChecker.verify(CandleCoverageChecker.java:71)
기준선 md5: 실행 후에도 c2d8460d... (변경 없음)
```

**휴장일 판정 검산**(결측 목록에서 정확히 빠진 날): 12-19·20(토·일), 12-25(성탄절),
12-26·27(토·일), **12-31(연말 휴장)**. 오판 0건.
→ 판정: 통과. 채점 **전**에 멈추고, 사람이 읽고 조치할 수 있는 문장을 남긴다.

## 5. 스프링 배선 — 통과, 단 단서 있음

```
명령: gradle bootRun --args="--spring.profiles.active=backtest --backtest.mode=smoke"
결과: Started TradingApplication in 3.608 seconds
      BeanCreationException / NoUniqueBeanDefinition / NoSuchBeanDefinition / UnsatisfiedDependency: 0건
종료 코드: 1  ← 아래 사유
```

**종료 코드 1의 원인은 이번 변경이 아니다.** 스택트레이스 확인 결과
`EGW00133 접근토큰 발급 잠시 후 다시 시도하세요(1분당 1회)` — 검증 과정에서 백테스트를
연속 4회 실행해 **KIS 토큰 발급 분당 한도**에 걸린 것이다(`KisApiClient.refreshToken`,
백필 단계). 컨텍스트 기동과 빈 생성은 이미 성공한 뒤였다.

신규 빈 3개(`BaselineStore`·`BaselineDriftReporter`·`CandleCoverageChecker`)의 런타임 동작은
**§2·§3·§4의 성공한 실행들이 직접 증명**한다(대조 절 출력 + 관문 예외 발생).
→ 판정: 통과. 단 "smoke 모드 단독 종료코드 0"은 이번에 확인하지 못했다(외부 API 한도 사유).

## 6. 범위 무오염

```
git status --porcelain -- src/main/java/com/trading/backtest/ src/test/java/com/trading/backtest/
→ 수정 5 + 신규 5 + 테스트 신규 3·보강 2 — 전부 com.trading.backtest
```
`order`·`risk`·`control`·`market`·`position`의 미커밋 변경은 **다른 두 트랙(08-12)의 것**이며
이번 변경과 무관(귀속은 `_workspace/5_verify_backtest-refactor.md` §4에서 이미 분리 확인).

## 7. 이번에 하지 않은 것 (추측하지 않음)

- **smoke 모드 종료코드 0 재확인** — KIS 토큰 분당 한도로 미완. 1분 이상 띄우고 재실행하면 확인 가능.
- **다른 모드(exit-lab·regime-lab·donchian-*·crash-vol)의 과거 기준선·앵커**가 같은 슬랙에
  걸렸는지 — 범위 밖. 앞으로의 실행에는 두 안전장치가 걸리지만, **이미 기록된 앵커는 미점검**이다.
- **운영(paper) 앱 점검** — 이 작업은 백테스트 전용이라 범위 아님. 앱을 켜지 않았다.
- `risk-auditor`가 지적한 MEDIUM 4건(관문 우회 경로 2곳·부동 창 꼬리 결측·종목별 부분 결측)의
  **실증 재현은 하지 않았다** — 정적 지적이며 전부 pre-existing이다. BACKLOG 등재로 처리.

## 8. 산출물 상충 기록 (삭제하지 않고 병기)

구현자 보고 `8_impl` §6.2의 **"부동 창 모드는 고정 창과 움직이는 상한의 어긋남이 구조적으로 없다"**는
주장을, 감사(`9_audit` MEDIUM 2)가 **반증**했다(부동 모드의 판정 창 끝 = `rangeTo()`이므로
슬랙 안에서 최대 7달력일 뒤처진 채 채점될 수 있다). 이 검증에서는 어느 쪽도 실증하지 않았다 —
**두 문서를 모두 남기고 사용자 판단 사항으로 올린다.**
