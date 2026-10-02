---
name: spring-build-verify
description: "이 자동매매 프로젝트의 빌드·테스트를 실제로 실행하고 결과를 증거로 보고하는 절차. 빌드/테스트/컴파일 실행, 'gradlew가 멈춘다', 'ClassNotFoundException으로 테스트가 전부 죽는다', 'TRADING_BUILD_DIR', 모의투자 실행(run-paper), 백테스트 실행(--spring.profiles.active=backtest) 요청 시 반드시 이 스킬을 사용. 테스트 통과·빌드 성공을 주장하기 전에도 이 스킬을 먼저 쓴다. 재실행·재검증·수정 후 다시 확인 요청에도 사용."
---

# spring-build-verify — 빌드·테스트 실행과 증거 수집

예전에는 이 프로젝트 경로에 **한글(`개발`)이 들어 있어 그냥 실행하면 깨졌다** —
Gradle 테스트 워커가 클래스패스를 percent-encode하지 못해 테스트가 전부
`ClassNotFoundException`으로 죽었다. **2026-07 부모 폴더를 `workspace`(ASCII)로
rename해 근본 원인이 해소됐다.** 아래 `TRADING_BUILD_DIR` 우회는 더는 필수가 아니지만
무해한 안전망이므로 설정해 두어도 좋다.

## 1. 빌드 출력 경로 (선택 — 안전망)

```bash
export TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build
```

`build.gradle`이 이 환경변수를 읽는다. 경로가 ASCII가 된 지금은 미설정이어도
테스트가 정상 동작한다. 그래도 만약 `ClassNotFoundException`으로 테스트가 통째로
실패하면 코드를 의심하기 전에 빌드 출력 경로부터 확인한다.

## 2. 실행 명령 (우선순위 순)

### 2-1. PowerShell / cmd — 정석 경로

```
.\gradlew.bat test
.\gradlew.bat build
.\gradlew.bat test --tests "com.trading.risk.LiquidationServiceTest"
```

### 2-2. Git Bash에서 `gradlew.bat`이 멈추면 — 캐시된 배포본 직접 호출

```bash
TRADING_BUILD_DIR=C:/Users/SAMSUNG/auto_trading-build \
  "/c/Users/SAMSUNG/.gradle/wrapper/dists/gradle-9.2.0-bin/11i5gvueggl8a5cioxuftxrik/gradle-9.2.0/bin/gradle" \
  test --console=plain
```

> 배포본 경로의 해시(`11i5gv...`)는 gradle 버전이 바뀌면 달라진다.
> `gradle/wrapper/gradle-wrapper.properties`의 버전을 확인하고
> `ls ~/.gradle/wrapper/dists/`로 실제 폴더명을 찾아 쓴다.

### 2-3. 애플리케이션 실행

| 목적 | 명령 |
|---|---|
| 모의투자 | `run-paper.bat` 또는 `.\gradlew.bat bootRun --args="--spring.profiles.active=paper"` |
| 백테스트 | `.\gradlew.bat bootRun --args="--spring.profiles.active=backtest"` |
| 백테스트 모드 지정 | 위 명령에 `--backtest.mode=smoke\|ma-breakout\|scalping\|exit-lab\|risk-lab\|events` 추가 |

## 3. 알아둘 것 — 정상인데 이상해 보이는 현상

| 현상 | 원인 | 대응 |
|---|---|---|
| 빌드마다 10~20초 지연 | `gradle.properties`에 `org.gradle.daemon=false` — 매번 데몬 새로 포크 | 정상. 기다린다 |
| 테스트 전부 `ClassNotFoundException` | `TRADING_BUILD_DIR` 미설정 | 1번 설정 후 재실행 |
| `gradlew.bat`이 응답 없음 | Git Bash 환경 문제 | 2-2 직접 호출로 전환 |
| 대시보드 화면 변경이 반영 안 됨 | 정적 리소스가 빌드 출력에서 서빙됨 | `bootRun` 재시작 필요 |
| 구체 클래스 목킹이 불안정 | Java 25 + 인라인 Mockito 제약 | 인터페이스만 목, 서비스는 실객체 |

## 4. 증거 보고 형식 (필수)

실행 결과는 반드시 아래 형식으로 남긴다. **이번에 직접 실행한 것만 적는다** —
이전 실행 결과를 재인용하지 않는다. 코드가 바뀌었으면 다시 돌린다.

```
[검증 증거]
명령: gradle test --console=plain
종료 코드: 0
결과: 34개 중 34개 통과, 실패 0
→ 판정: 통과
```

실패했을 때:

```
[검증 증거]
명령: gradle test --console=plain
종료 코드: 1
결과: 34개 중 32개 통과, 실패 2
  - LiquidationServiceTest.trim양보 : expected FULL but was TRIM
  - StopLossArmerTest.부분체결 : NullPointerException at StopLossArmer.java:57
→ 판정: 실패 — 완료 아님
```

**금지 표현:** "통과할 것", "문제없어 보임", "아마 정상". 숫자와 종료 코드로만 말한다.

## 5. 회귀 테스트는 Red-Green으로 확인한다

버그를 고쳤다고 주장하려면 테스트가 **그 버그를 실제로 잡는지** 확인해야 한다.
한 번 통과한 것만으로는 아무것도 증명하지 못한다.

```
1. 테스트 작성 → 실행 → 통과   (테스트 자체가 동작함)
2. 수정을 되돌림 → 실행 → 실패  (테스트가 버그를 잡음)  ← 이게 핵심
3. 수정을 복구 → 실행 → 통과   (수정이 버그를 없앰)
```

2번에서 실패하지 않으면 그 테스트는 버그와 무관한 것을 검사하고 있다.

## 6. 백테스트를 돌릴 때 지킬 것

- 백테스트는 전용 `backtest-db`를 쓴다. paper 운영 DB를 가리키게 바꾸지 않는다
- **`trading.bucket.enabled`(지갑 칸)를 backtest에서 켜지 않는다** — 결정성이 깨진다
- `@EnableScheduling`은 `SchedulingConfig`(`@Profile("!backtest")`)에 있어야 한다.
  애플리케이션 클래스로 옮기면 백테스트가 비결정적이 된다
- 백테스트 리포트는 `logs/backtest/REPORT-*.md`에 생성된다. 요약만 인용하고
  원본은 지우지 않는다
