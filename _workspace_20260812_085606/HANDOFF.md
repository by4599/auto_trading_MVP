# 인수인계 — 폴더명 변경(개발→workspace) 마무리

> 이 파일은 폴더 이동 **직전** 세션이 남긴 것이다. 새 경로
> `C:\Users\SAMSUNG\Desktop\workspace\auto_trading` 에서 열린 새 세션의 Claude가 읽고 이어간다.
> 사용자가 "HANDOFF 읽고 폴더 이동 마무리해줘"라고 하면 아래 1~5를 순서대로 수행한다.

## 지금 상황 (왜 이 파일이 있나)
사용자가 부모 폴더 `개발`을 `workspace`로 이름 변경하기로 했다(경로에 한글이 있어 Gradle test worker가
깨지던 문제의 근본 해결). 이전 세션이 **앱을 정지**하고 이 메모를 남겼다. 사용자가 탐색기에서
`Desktop\개발` → `Desktop\workspace`로 rename 후, 새 경로에서 Claude Code를 다시 열었다.

## 해야 할 일 (순서대로)

### 1. 경로 참조 수정 (폴더명이 개발→workspace로 바뀌었으니)
- `.claude/settings.json` — 훅 command 2줄의 `Desktop/개발/auto_trading` → `Desktop/workspace/auto_trading`
  (SessionStart 훅, precommit 훅)
- `.claude/settings.local.json` — permission 문자열의 `Desktop/개발/auto_trading`(및 `개발`) → `workspace`.
  ⚠ 같은 파일 6행의 메모리 경로 `C--Users-SAMSUNG-Desktop----auto-trading`는 아래 3번(메모리 이관)에서 별도 처리.

### 2. 낡은 문서 갱신 (이제 경로가 ASCII라 한글-경로 문제 서술이 무효)
- `build.gradle` (10행 근처) — "한글('개발') 경로라 깨진다" 주석을 현실에 맞게: 이제 ASCII 경로라
  근본 원인 해소. `TRADING_BUILD_DIR` 메커니즘은 무해하니 남겨도 되나, "더는 필수 아님"으로 서술 갱신.
- `CLAUDE.md` (빌드 섹션, 40·44·50행 근처) — 한글 경로 워크어라운드 설명 갱신.
- `README.md` (174행 근처), `.claude/skills/spring-build-verify/SKILL.md` (8행 근처) — 동일 취지 갱신.
- ⚠ **절대 건드리지 말 것**: "개발 환경"·"개발·운영"처럼 **낱말 "개발"(=development)**이 쓰인 곳
  (`application-paper.yml`, `TelegramProperties.java`, `CLAUDE.md` 23행, `README.md` 26행). 폴더명 아님.

### 3. 메모리 이관 (안 하면 프로젝트 메모리가 빈 상태로 리셋됨)
- 옛 메모리 디렉터리: `C:/Users/SAMSUNG/.claude/projects/C--Users-SAMSUNG-Desktop----auto-trading/memory/`
- 새 세션의 프로젝트 키를 확인하고(새 경로 기반), 옛 `memory/` 내용(MEMORY.md + 개별 .md)을 새 키
  디렉터리로 복사. (새 키 디렉터리 경로는 새 세션의 시스템 프롬프트/메모리 안내에서 확인)

### 4. 빌드·테스트 검증 (한글 경로 해소 확인)
- 전체 테스트 실행 → **408개 통과** 기대. 이제 경로가 ASCII라 `TRADING_BUILD_DIR` 없이도 깨지지 않아야
  한다(그래도 설정돼 있으면 무해). 실행: `spring-build-verify` 스킬 절차. 종료 코드 0 확인.

### 5. 모의투자 앱 재시작 (새 경로에서)
- `run-paper.bat` 또는 `.\gradlew.bat bootRun --args="--spring.profiles.active=paper"` (백그라운드).
- 확인: 앱 RUNNING, **유니버스 20종목 그대로**(DB가 폴더와 함께 이동 — `jdbc:h2:file:./trading-db` 상대경로),
  **스트릭 5/달성 그대로**. `curl localhost:8080/api/trading/run-streak` 와 `.../api/universe`로 검증.

## 현재 프로젝트 상태 (맥락)

- **PR #1 열림**: `backtest/regime-filter-and-validation` → master.
  https://github.com/by4599/auto_trading_MVP/pull/1 (손익비 후보 검증 트랙 — cost-lab·재현성·crash-vol·
  데이터확장·지수추세필터+민감도, 408 테스트 통과). 커밋 이후 이 폴더-이동 관련 변경(참조·문서 수정)이
  생기면 같은 브랜치에 커밋·푸시하면 PR #1에 붙는다.
- **릴리즈 체크리스트**:
  - ✅ 모의투자 5거래일 연속 무중단 — **2026-07-27 달성**
  - ⏳ 강제청산 리허설(Gate 2) — **대기**. 앱이 종목을 보유한 장중에 사용자가
    `POST /api/trading/liquidation-drill {"confirm":"CONFIRM_LIQUIDATE"}` 를 **직접 누름** → Claude가
    로그·텔레그램으로 매도 체결 검증 → `POST /api/trading/resume {"confirm":"CONFIRM_RESUME","reason":"..."}` →
    그다음 SCOPE_GUARDIAN Gate 2를 ☑로. **유니버스를 20종목으로 늘려** 보유 생길 확률을 높여둠.
  - 부분체결 연속손실 집계 등 나머지는 SCOPE_GUARDIAN.md 참고.
- **매매 유니버스 20종목** (상한): 005930·035420·000660·005380·000270·105560·055550·012330·006400·
  051910·207940·000810·034730·086790·066570·267260·028260·032830·012450·034020. (MAX=20 유지 결정)
- **검증된 백테스트 후보**: MA 정배열 진입 + P3 다일 트레일링 출구 + RR1(0.5R·동시5) + 지수 MA120 이탈
  진입금지 필터 → §4 전 기준 통과(BACKTEST-DESIGN §14.4). **단 실전 준비 완료 아님**: 생존편향·코로나
  미채점·크래시형 MDD(여유 1.7%p). 실전 전환 = ADR-001(다일 보유) 재논의 + 게이트 G2(사람) 선행.
  ⚠ paper에서 도는 건 이 후보가 아니라 백테스트에서 떨어진 옛 전략(VB/MA돌파/스캘핑)이다.
- **ntfy 알림 연계**: 논의만 함, 대기(사용자 요청 시). 앱 이벤트를 `Notifier` 인터페이스로 빼고
  `NtfyNotifier` 추가하는 방식(텔레그램과 병렬). 공개 토픽 프라이버시 주의.
- **리마인더 루틴** trig_01KaLDdVWe9d6SiNq4EuzQ37: 07-27 10:00 일회성 — 이미 발동 완료.

## 안전 규칙 (변함없음)
- 실전(real) 계좌 전환·실제 주문·강제청산 실행은 **사람이 결정하고 사람이 누른다.** Claude는
  준비·검증·안내까지.
- 백테스트 기준선 `docs/BACKTEST-BASELINE-EXITLAB-MA-P3.yml`(780/1.959/period 2023-07-22~2026-07-21)은
  수동 편집·덮어쓰기 금지.
