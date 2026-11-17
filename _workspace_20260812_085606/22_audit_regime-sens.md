# 22_audit_regime-sens — regime-sens 모드(지수 추세 MA 기간 민감도, §14.4) 감사

감사일 2026-07-25 · 대상: regime-sens 변경분(mtime 07:33~07:35) · **소스만 읽음**
(다른 에이전트가 regime-sens 실행 중일 수 있어 Gradle 미실행 — TRADING_BUILD_DIR 공유.
단위 테스트는 정적 상수만 보는 순수 테스트라 코드 대조로 검증).

## 결론 (한 줄)
**CRITICAL 0건 · HIGH 0건** · **regime-lab 불변 확인**(mtime 격리 + 값 대조 + 회귀 테스트) —
regime-sens 결과 해석 가능. 선견편향 재발 없음(판정 소스 무수정), 기준선 yml 무변경.

## mtime 격리 (regime-sens 변경분 특정)
| 파일 | mtime | 소속 |
|---|---|---|
| BacktestOrchestrator.java | 07:34:58 | **regime-sens** |
| BacktestReportWriter.java | 07:33:04 | **regime-sens** |
| BacktestOrchestratorRegimeProfilesTest.java(신규) | 07:35:30 | **regime-sens** |
| IndexTrendRule.java | 00:28:32 | regime-filter(무수정) |
| BacktestIndexRegimeSource.java | 00:35:17 | regime-filter(무수정) |
| IndexRegimeSource.java | 00:28:14 | regime-filter(무수정) |
| FilterProperties.java | 00:27:58 | regime-filter(무수정) |

→ regime-sens는 **오케스트레이터·리포트라이터·신규 테스트 3개만** 건드렸다.
판정 로직 소스(IndexTrendRule/BacktestIndexRegimeSource/IndexRegimeSource)는 손대지 않았다.

## 1. regime-lab 불변 (최우선) — 확인
| 검사 | 결과 | 근거 |
|---|---|---|
| REGIME_PROFILES = [OFF/200, MA120, MA200] 유지 | OK | Orchestrator diff `:473-476` · 회귀 테스트 `regimeLabProfilesUnchanged`가 [OFF/200,MA120,MA200] 못박음(test `:48-61`) |
| runRegimeLab이 3프로필·tag="RegimeLab"·total=3 사용 | OK | diff `:500-516` — `runRegimeProfile(…, REGIME_PROFILES.size()(=3), "RegimeLab", …)` |
| applyRegimeFixedConditions 값 = 18_impl 문서와 동일 | OK | diff `:491-498` — VB off·MA on·scalping off·applyExitProfile(P3 **1.0/false/20/true/0.01/0.03**)·riskFraction **0.005**·maxPos **5**. 18_impl §90-92 기재값과 일치 |
| restoreRegimeDefaults 값 동일 | OK | diff `:552-558` — indexTrend OFF/200·resetExitProfile·riskLimits 상수(RISK_FRACTION_PER_TRADE/MAX_POSITION_COUNT) 복원. 18_impl §103 기재와 일치 |
| runRegimeProfile tag/total 파라미터화가 문자열 불변 | OK | diff `:527` `"[{}] ({}/{}) {} 시작"` → tag="RegimeLab"·total=3 → `"[RegimeLab] (1/3) …"`. 하드코딩판과 문자 동일. judgeData는 REGIME_LAB_LABEL/SLUG 상수 사용(`:536-537`) — label은 표기용, judge는 건수·PF·MDD만 봄(19_audit 확인) |
| logCrisisWindows tag 파라미터화 | OK | diff `:564-574` — tag="RegimeLab" 전달 시 `"[RegimeLab] … 2022 창 …"` 동일 |
| 리포트 경로 불변 | OK | diff `:513-514` writeRegimeLabReport → writeComparison(…, **false, null**) 그대로(baseline 미기록) |

**판단 보류(근거 병기)**: 전체 §14 작업이 미커밋이라 regime-sens **직전** 바이트 스냅샷이 없어
"바이트 단위 동일"은 git으로 못 박는다. 다만 (a)공유 헬퍼가 담은 값이 18_impl 문서값과
일치하고 (b)regime-lab이 원래 인자("RegimeLab",3)로 호출하며 (c)회귀 테스트가 프로필 리스트를
못박으므로, **동작·숫자·로그 불변으로 판단**. 확정하려면 실행에서 G0=1591·PF1.26·MDD33.5%
회귀 앵커 재현으로 최종 확인(strategy-quant 몫).

## 2. 선견편판 재발 없음 — 확인
| 검사 | 결과 | 근거 |
|---|---|---|
| 판정 소스 무수정 | OK | IndexTrendRule/BacktestIndexRegimeSource/IndexRegimeSource mtime=00:28~00:35(regime-sens 07:34 이전). regime-sens는 이 파일들을 안 건드림 |
| MA 기간만 96/120/144로 스윕, 판정 로직 동일 | OK | runRegimeProfile `:525-526`이 filters.getIndexTrend().setMaPeriod(rp.maPeriod())만 바꿈. belowTrend(closes, simDate, maPeriod)는 기간 무관(최근 N개·전일 종가·headMap(simDate,false)) — 19_audit 검산 9종 통과분 그대로 |
| S1/S3(96/144)도 같은 차단선 통과 | OK | belowTrend는 maPeriod에 파라미터라 96·144도 당일 봉 미참조 동일. 새 판정 경로 없음 |

## 3. 기존 모드·기준선 불변 — 확인
| 검사 | 결과 | 근거 |
|---|---|---|
| 기준선 yml 무변경 | OK | md5 **852a29da9fd5d40d8be9b5c2f2904ba0** · mtime 2026-07-23 01:20(오늘 아님) · 780건·PF1.959·period 2023-07-22~2026-07-21 |
| full/…/risk-lab/cost-lab/crash-vol/regime-lab 분기 무변경 | OK | Orchestrator 각 모드 분기 diff에 regime-sens 특화 삽입 없음. regime-sens는 regime-lab 분기 뒤 신규 분기 1개만 추가(`:142-147`) |
| writeComparison/judge/writeBaseline 무영향 | 판단보류→실질 무영향 | writeComparison(diff `:366-455`)에 regime 특화 분기 없음(rows 범용). regime-sens는 writeRegimeSensReport(신규, `:249-269`)가 writeComparison을 **호출만**(false,null). judge는 §4 임계선 그대로 |
| applyExitProfile/runRiskLab 무수정 | 판단보류→실질 무영향 | 두 메서드에 regime 코드 없음. 미커밋이라 바이트 확정은 회귀 앵커(RR0·C0) 재현으로 |

## 4. 프로필 격리·비밀키 — 확인
| 검사 | 결과 | 근거 |
|---|---|---|
| 새 분기 backtest 전용 | OK | BacktestOrchestrator는 @Profile("backtest") CommandLineRunner. regime-sens 분기는 mode 문자열 비교(`:142`)로만 진입 |
| 알림/주문 경로 없음 | OK | 신규 코드에 OrderEngine·텔레그램 참조 없음(로그·리포트만). 규칙1·2 흐름 불변 |
| 지갑 칸 유출 없음 | OK | bucket 키 미참조, application-backtest.yml 무변경 |
| 비밀키 하드코딩 없음 | OK | 변경 3파일에 appkey/secretkey 없음, yml 키 미추가 |

## 5. REGIME_SENS_PROFILES 값 — 확인
diff `:480-484` = [S0 OFF(false,**200**), S1 MA**96**(true), S2 MA**120**(true), S3 MA**144**(true)].
S0은 비활성이라 maPeriod 200이 동작 무영향(§14.4 G0 앵커 동일성 명시용). 96=120×0.8, 144=120×1.2 —
테스트 `:41-43`이 못박음. **정확히 [OFF, 96, 120, 144]** 확인.

## 이전 감사(19_audit) HIGH·MEDIUM 현재 상태
| 출처 | 항목 | 상태 |
|---|---|---|
| 19_audit HIGH-1 | 기존 갭다운 필터 백테스트 무발동 | **미해소(문서 정정 대기)** — 단 regime-sens는 신규 IndexTrendRule(리포지토리 직독) 경로라 결과 유효성에 무관 |
| 19_audit MEDIUM(allFiltersOff 미갱신) | indexTrend가 allFiltersOff 목록에 없음 | 유지 — regime-sens도 루프마다 명시 설정 + restoreRegimeDefaults 명시 OFF라 현재 안전 |
| 19_audit MEDIUM(real 빈 부재) | real에 IndexRegimeSource 구현체 없음 | 유지(선존) — 실전 전환은 게이트 G2 + 빈 추가 선행 |

## 항목별 신규 지적
| 심각도 | 항목 | 위치 | 내용 | 근거 |
|---|---|---|---|---|
| LOW | 파일 800줄 상한 초과 | BacktestOrchestrator.java **853줄** | 이미 알려진 사안(18_impl §182). regime-sens로 764→853. 랩별 클래스 분리는 별도 정리 범위 | 코딩스타일 800 상한 |
| LOW | regime-lab 바이트 스냅샷 부재 | (미커밋 전체) | 실행 회귀 앵커(G0=1591·PF1.26·MDD33.5%) 재현으로 최종 확정 필요 — strategy-quant 몫 | 미커밋 diff 한계 |

## 최종 집계
**CRITICAL 0건 · HIGH 0건 · MEDIUM 0건(신규) · LOW 2건**
- regime-lab **불변 확인** — mtime 격리(판정 소스 무수정) + 공유 헬퍼 값 대조(18_impl 일치) +
  회귀 테스트(REGIME_PROFILES 못박음). 잔여는 실행 회귀 앵커 재현 1건.
- 선견편향 재발 없음(판정 로직 무수정, MA 기간만 파라미터).
- 기준선 yml·기존 모드 무영향. 프로필 격리·비밀키 OK.
- **실전 전환은 어떤 결과든 별개**: ADR-001(다일 보유) 재논의 + 게이트 G2(사람) 선행,
  real 프로필 IndexRegimeSource 빈 부재(19_audit MEDIUM) 해소 전 기동 불가.
- 배포 판정: 리스크 관점 **차단 사유 없음**(측정 전용 백테스트 모드, paper/real 동작 불변).
