# 34_ops — paper 공시 쿨다운 OFF (A동 검증 설정과 맞춤)

> 2026-10-10(토) 00:00~ KST · 리더 · 사용자 결정(2026-10-09 "응 꺼줘") · 감사 `35_audit_disclosure-cooldown-off.md`

## 0. 결론

`trading.filters.disclosure-cooldown.enabled: true → false` (paper 전용 yml). A동 검증 설정(BACKTEST-DESIGN §15.7 D0)은
공시 쿨다운을 끈 채 채점했는데(`ExecutionKnobs.allFiltersOff()` 뒤 트레일링·지수 MA120만 다시 켬 — `backtest/ExecutionKnobs.java:39-60`,
`DonchianLab.java:86-91`, 35_audit §2-③) paper만 공시 쿨다운이 켜져 있어 paper 성적과 검증 성적의 분모가 어긋났다(`30_audit_trend-sleeve-switch.md` L-5, `29_impl_trend-sleeve-switch.md` §5-9).
쿨다운 일수 5는 재가동 대비 보존. 원래 VB 시절 B-3 A/B 채택분(BACKTEST-DESIGN §9)이다.

## 1. 바뀐 것

| 파일 | 변경 |
|---|---|
| `src/main/resources/application-paper.yml` | `disclosure-cooldown.enabled: false` + 이유 주석 2줄 |
| `src/test/java/com/trading/PaperTrendSleeveConfigTest.java` | 새 테스트 `disclosure_cooldown_off_as_in_backtest_d0` — 실제 paper yml을 바인딩해 OFF를 단언. 클래스 javadoc에 "공시 쿨다운 OFF" |
| `CLAUDE.md` | 리스크 룰 표 `DisclosureCooldownRule` 행 → ⏸ 꺼짐, B-1~B-3 항목의 "공시 쿨다운 5일 ON" 옆에 2026-10-09 OFF 표기 |

## 2. 저장값 덮어쓰기 점검 — yml만 바꾸면 안 되는 이유

`settings/TradingParamService.java:44-64` `loadOnStartup`이 기동 때 `app_setting` DB 행을 yml 위에 적용한다. 이 키
(`filters.disclosureCooldown.enabled`, `settings/ParamCatalog.java:117`)에 저장값이 있으면 yml을 false로 바꿔도 재시작 후 되살아난다.
재시작 전 관측: `GET /api/params` value "true" / defaultValue "false"(= yml 값), 10-01 12:55 기동 로그에 `[Param] 저장된 투자 파라미터`
줄 없음(적용 0건이면 이 줄이 찍히지 않는다). 확정은 재시작 후 `/api/params` value로 한다(§5).
⚠ 대시보드 설정 화면에서 다시 켜면 그 저장값이 다음 기동부터 yml을 덮어쓴다 — 새 테스트는 yml만 고정한다.

## 3. 검증 증거 (Red-Green)

```
[검증 증거 — Red]
명령: gradle test --tests "com.trading.PaperTrendSleeveConfigTest" --console=plain
      (TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build-verify34 — 운영 빌드 폴더와 분리)
종료 코드: 1
결과: 6개 중 5개 통과, 실패 1 — 새 테스트 AssertionFailedError at PaperTrendSleeveConfigTest.java:127 (yml이 아직 true)
→ 판정: 테스트가 스위치를 실제로 잡는다

[검증 증거 — Green]
명령: gradle test --console=plain (같은 빌드 폴더, yml false 적용 후)
종료 코드: 0
결과: 890개 중 890개 통과, 실패 0 · 오류 0 · 건너뜀 0 (직전 889 + 새 1)
      결과 XML 146개 전부 2026-10-10 00:09:43 이번 실행분. PaperTrendSleeveConfigTest 6/6
→ 판정: 통과
```

## 4. 별건 발견 — 10-06~10-08 3거래일 무가동 (PC 대기 모드)

| 근거 | 내용 |
|---|---|
| Windows System 로그 | Kernel-Power **506**(대기 진입) 2026-10-05 03:02:40 → **507**(대기 해제) 2026-10-09 21:04:48 |
| 앱 로그 | 10-05 03:02:45가 마지막 줄, 다음 줄 10-09 21:04:50. 21:05:08 HikariPool `Thread starvation or clock leap detected` |
| 프로세스 | 앱(PID 3376)은 10-01 12:55 기동 그대로 — 재시작이 아니라 **멈춰 있었다** |
| 연속 가동일 | `/api/trading/run-streak` = `streakDays 1 · lastRecordedDate 2026-10-02` |

영향: 거래일 10-06(화)·10-07(수)·10-08(목) 매매·감시 0(10-05 월·10-09 금은 휴장). 보유 0이라 무방비 노출은 없었다.
원인(덮개 닫힘·전원 버튼·모던 스탠바이 자동 진입 등)은 조사하지 않았다 — 09-15 기록상 절전은 AC·DC '안 함'인데 506은 모던 스탠바이
진입이라 별개 경로일 수 있다. PC 설정은 사용자 몫. 릴리즈 항목 "5거래일 연속 무중단"이 다시 끊겼다.

## 5. 재시작 기록 — 정지까지 완료, 기동·적용 확인 대기

| 단계 | 결과 |
|---|---|
| 감사 | `35_audit` CRITICAL 0 · HIGH 0 · MEDIUM 0 · LOW 5 → 재시작 적용 가능(조건 2개). L-3 문구는 고친 뒤 전체 테스트 재실행 **890/890 통과**(종료 코드 0, 결과 XML 146개 전부 00:44:28 실행분, 바뀐 표시명이 리포트에 찍힘) |
| 백업 | `backup/trading-db-20261010_001120.zip`(21.3 MB) · `backtest-db-20261010_001120.zip`(4.4 MB), 종료 코드 0 |
| 정지 전 상태 | 00:44 `/api/status` RUNNING · `/api/position` [] · `/api/orders/open` [] — 보유·미체결 0 |
| 정지 | 00:44 `Stop-Process` PID 3376(앱)·11356(gradle 데몬)·27244(gradlew) → paper java 0개 · 8080 free · 작업 `AutoTrading-Paper-0830` Ready · `logs/auto-start.log` `2026-10-10 00:44:57 [0830] 앱 종료 (코드 -1)` |
| 기동 | **리더는 하지 못했다.** 작업 스케줄러가 부르는 기동기 `start-paper-if-needed.ps1:30-33`은 토·일에 기동을 건너뛴다(설계). 남은 길은 `run-paper` 기동기를 직접 실행하는 것인데, 이 세션에서 .bat 파일을 언급한 셸 명령은 9건 모두 실행되기 전에 멈췄다(보안 모듈의 .bat 차단 — `start-paper-if-needed.ps1:12-13`, OPERATIONS §1.1 — 또는 실행 승인 단계로 보이며 원인은 확인하지 않았다). 우회하지 않았다. → **사용자가 탐색기에서 `run-paper`를 더블클릭**하거나 **10-12(월) 08:30 자동 기동**(평일)으로 켜진다. 둘 다 bootRun 경로라 운영 빌드의 yml 사본이 새 값으로 덮인다(35_audit 조건 1) |
| 적용 확인 | **대기.** 기동 후 순서(35_audit §2-②): ① 새 PID `[main]` 줄에서 `Started TradingApplication` + `[Reconciler] 기동 재동기화 완료` ② `GET /api/params` → `filters.disclosureCooldown.enabled` value **"false"**(유일한 확정 증거) ③ 새 PID `[main]`에 `[Param] 저장된 투자 파라미터` 없음 ④ `Loaded 15 risk rules` 그대로. 그 전까지 30_audit L-5는 "해소 대상"이다 |

⚠ 기동 직후에는 지수 판정이 비어 있어, 다음 거래일 첫 조회(개장 1분 뒤) 전까지 `IndexTrendDataGateRule`이 신규 매수를 막는다(35_audit I-2, 의도된 동작).
⚠ ②에서 "true"가 나오면 저장값이 있다는 뜻이다 — 대시보드 "기본값 원복"을 누르지 말 것(전역 트레일링까지 꺼진다). 35_audit §2-②의 두 방법 중 하나로 처리한다.
