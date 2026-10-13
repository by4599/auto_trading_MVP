# 38_ops — paper 누적 MDD 가드 10% → 8% 적용 (ADR-001 §2.2)

> 2026-10-10(토) 09:50 KST · 리더 · 사용자 요청("전체 손실 멈춤선을 10%에서 8%로", "감사 끝나면 적용하고 커밋 푸시까지")
> · 감사 `37_audit_mdd-guard-8pct.md`(CRITICAL 0 · HIGH 0 · MEDIUM 3 · LOW 4 → 적용 가능, 조건 5개)

## 0. 결론

paper 실효 한도 **8% 적용·확인 완료(런타임)**. 코드 무변경. 재기동 후에도 남는지(지속성)는 다음 재기동 때 확인한다.

## 1. 방법과 이유

- `RiskLimitsProperties`는 yml 바인딩이 아니라 상수 기본값을 가진 `@Component`다(`risk/RiskLimitsProperties.java:5-13`).
  바꾸는 길은 대시보드 파라미터(`settings/TradingParamService` → 운영 DB `app_setting`)뿐이다.
- 코드 상수 `RiskLimits.MDD_LIMIT`(0.10)를 바꾸면 백테스트(`backtest/BacktestRunner.java:170`)가 같은 값을 읽어 회귀 앵커가
  흔들린다 → 제외. 백테스트는 `app_setting`을 읽지 않는다(37_audit §2 — CommandLineRunner + System.exit로 ApplicationReadyEvent 미발행).
- 그래서 `PUT /api/params {"risk.mddLimit":"0.08"}` 한 번 — paper만, 즉시, 재시작 불필요.
- 리더가 적용한 근거: 주문·청산 실행 버튼이 아니라 안전 한도 설정값이고 돈이 움직이지 않는다(메모리 원칙 "주문·청산 실행은
  사람이"의 범위 밖). 사용자가 적용을 명시 요청했다. 적용 시점 낙폭 −3.3% < 8%, 토요일 → 즉시 발동 없음(37_audit §1).

## 2. 증거 (37_audit 조건 1·2)

| 확인 | 결과 |
|---|---|
| 적용 전 `GET /api/params` | value "0.1" · defaultValue "0.1" |
| `PUT /api/params` 응답 (09:50:22) | `{"saved":["risk.mddLimit"],"errors":{}}` |
| 적용 후 `GET /api/params` | value **"0.08"** · defaultValue "0.1"(정상) |
| `GET /api/performance/account` | mddLimitPercent **8.0** · forcedStopThreshold **9,281,702** · roomToThreshold **474,123** · currentEquity 9,755,825 · peakEquity 10,088,806 · 낙폭 −3.3% |
| 로그 | `09:50:23 [Param] risk.mddLimit : 0.1 → 0.08` (PID 22036) |
| 상태 | RUNNING |

## 3. 남은 것 · 주의

- **지속성 확인 대기**: 다음 재기동 후 `GET /api/params`가 0.08인지 보기 전에는 "지속 적용 완료"가 아니다(조건 4).
  `RiskMonitor` 기동 로그의 "MDD 한도 10.0%"는 DB 값 적용 전에 찍히는 줄이라 무시한다(L-1). 이제 기동 로그의
  `[Param] 저장된 투자 파라미터 1건 적용 완료`는 정상이다 — 34_ops의 "`[Param]` 0건 ⇒ 저장값 없음" 확인법은 더 못 쓴다.
- **git 밖에 있다(M-1)**: 대시보드 "기본값 원복"(전역 트레일링까지 코드 기본값으로 원복) · DB 재생성 · 옛 백업 복원 시
  조용히 10%로 돌아간다. 원복 버튼은 누르지 않는다(조건 3). 실전 전 정본화(커밋되는 yml 바인딩 등) 필수.
- **전고점 오염에 더 예민해졌다(M-3)**: 정지를 부르는 오염 크기 +7.44% → +5.11%. 한 번 넘으면 사람이 전고점이나 한도를
  고칠 때까지 매 개장 정지(09-10~09-21과 같은 모양). 장 밖 갱신은 10-01부터 막혔고, 장중 오염 대비가 남은 과제다.
- 하루 −4.86% 손실이면 일일 −5% 룰보다 MDD가 먼저 발동한다(일일 룰은 다음 날 풀리고 MDD는 안 풀린다).
- **별건 기존 결함(M-2)**: 문자열 `"NaN"`이 범위 검증을 통과해 MDD 가드를 영구히 끈다(`settings/ParamCatalog.java:172-176`).
  화면 입력칸으로는 못 넣는다. `Double.isFinite` 검사 추가 권고 — 사용자 결정 대기.
- 실전 프로필에는 `@Profile`에 real이 들어간 클래스가 0개다 — 실전 전환 감사 때 값보다 가드 존재부터 확인할 것.
- `30_audit` M-4: **부분 해소**(런타임만 · git 정본 없음 · 슬리브 상한 미구현). 게이트 G2 선행 조건으로 유지.
