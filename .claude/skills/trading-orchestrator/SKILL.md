---
name: trading-orchestrator
description: "자동매매 시스템 작업을 전문 에이전트 팀으로 조율하는 오케스트레이터. 전략 추가·수정, 백테스트 검증, 리스크 룰 변경, 주문·체결 파이프라인 수정, 운영 신뢰성 개선, 릴리즈 갭 해소 등 이 프로젝트의 실질적 작업 요청 시 반드시 이 스킬을 사용. 후속 작업에도 사용 — 다시 실행, 재실행, 업데이트, 수정, 보완, 이전 결과 개선, '백테스트만 다시', '감사만 다시', '검증만 다시', '어제 하던 거 이어서'. 단순 질문(무슨 파일이야, 이게 무슨 뜻이야)은 이 스킬 없이 직접 답한다."
---

# trading-orchestrator — 자동매매 작업 조율

이 프로젝트의 작업은 **돈이 걸려 있고, 안전장치가 조용히 깨질 수 있다.**
그래서 혼자 처리하지 않고 역할을 나눠 서로 교차 확인시킨다.

## 실행 모드: 에이전트 팀 (기본) · 서브 에이전트 (대체)

`TeamCreate`를 쓸 수 있으면 팀 모드로, 없으면 `Agent` 도구로 같은 역할을 호출한다
(팀 기능은 `CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1`이 필요하다). 어느 쪽이든
**모든 에이전트 호출에 `model: "opus"`를 명시**한다.

## 팀 구성

| 팀원 | 타입 | 역할 | 쓰는 스킬 | 출력 |
|---|---|---|---|---|
| `scope-guardian` | 커스텀 (기존) | 문지기 — 갭 체크·범위 판정 | — | `SCOPE_GUARDIAN.md`, `BACKLOG.md` |
| `strategy-quant` | 커스텀 | 전략 설계·백테스트 판정 | `backtest-verdict` | `_workspace/{n}_quant_*.md` |
| `risk-auditor` | 커스텀 | 안전장치 감사 | `risk-rule-audit` | `_workspace/{n}_audit_*.md` |
| `trading-implementer` | 커스텀 | 코드 구현 | `spring-build-verify` | 코드 + `_workspace/{n}_impl_*.md` |
| `paper-ops-verifier` | 커스텀 | 증거 검증·운영 점검 | `spring-build-verify`, `paper-ops-check` | `_workspace/{n}_verify_*.md` |

**전원을 매번 부르지 않는다.** 요청 유형에 따라 3~4명만 소집한다:

| 요청 유형 | 소집 |
|---|---|
| 전략 검증/백테스트 | scope-guardian → strategy-quant → paper-ops-verifier |
| 코드 구현/수정 | scope-guardian → trading-implementer ⇄ risk-auditor → paper-ops-verifier |
| 안전성 점검만 | risk-auditor (+ paper-ops-verifier) |
| 운영 점검·재가동 | paper-ops-verifier 단독 |
| 릴리즈 갭 해소 | scope-guardian → 해당 담당 → paper-ops-verifier |

## 워크플로우

### Phase 0: 컨텍스트 확인

1. `_workspace/` 존재 여부를 본다
   - **없음** → 초기 실행. Phase 1로
   - **있음 + 부분 수정 요청** → 부분 재실행. 해당 에이전트만 다시 부르고,
     나머지 산출물은 그대로 둔다
   - **있음 + 새 주제** → 새 실행. 기존 `_workspace/`를
     `_workspace_{YYYYMMDD_HHMMSS}/`로 옮긴 뒤 새로 만든다
2. 부분 재실행이면 이전 산출물 경로를 에이전트 프롬프트에 넣어, 읽고 이어가게 한다

### Phase 1: 갭 체크 게이트 (건너뛰지 않는다)

이 프로젝트의 규칙이다 — 구현 전에 반드시 `SCOPE_GUARDIAN.md` 기준 갭 체크를
출력한다. 깊은 코드 대조가 필요하면 `scope-guardian`을 호출한다.

```
[릴리즈 갭 체크]
- ⚠ / ☐ / ☑ 항목별 상태와 근거
→ 이번 세션 우선순위 제안 1~2개
```

그다음 **이번 요청을 분류**한다:

| 분류 | 조치 |
|---|---|
| 체크리스트 8항목 중 하나를 완성시킴 | 바로 진행 |
| 없으면 릴리즈 불가 | 체크리스트 추가 여부를 **사용자에게 먼저 확인** (임의 추가 금지) |
| 있으면 더 좋은 수준 | `BACKLOG.md`에 기록하고 이번 작업에서 제외 |

### Phase 2: 팀 소집과 작업 등록

1. 위 표에서 소집 대상을 정한다
2. 팀 모드: `TeamCreate(team_name: "trading-team", members: [...])`
   → 각 멤버에 `model: "opus"`와 역할 프롬프트
3. `TaskCreate`로 작업을 등록하고 의존성을 건다
   (예: "감사"는 "구현"에 `depends_on`)
4. 서브 모드: 독립 작업은 한 메시지에서 `Agent`를 동시 호출
   (`run_in_background: true`), 의존 작업은 순차 호출

### Phase 3: 실행 — 생성⇄검증 루프

핵심 규칙: **모듈 하나가 끝날 때마다 즉시 감사·검증한다.** 전부 만든 뒤 한 번에
검사하면 문제를 늦게 발견해 되돌리기 비싸진다.

```
trading-implementer: 모듈 완료
        ↓ SendMessage
risk-auditor: 즉시 감사 → CRITICAL/HIGH 있으면 되돌려 보냄
        ↓ 통과
paper-ops-verifier: 빌드·테스트 실제 실행 → 증거 표
        ↓ 실패면 다시 implementer
```

- 재작업 루프는 **최대 3회**. 3회에도 해결 안 되면 멈추고 사용자에게 보고한다
  (무한 루프 방지)
- 전략 작업이면 `strategy-quant`가 판정표를 내기 전까지 다음 단계로 가지 않는다

### Phase 4: 통합 보고

1. `_workspace/`의 산출물을 모두 읽는다
2. 아래 형식으로 종합한다 — **미해결 항목을 숨기지 않는다**

```
[작업 결과] {주제}
- 바뀐 것: (한 줄)
- 검증 증거: 테스트 34/34 통과, 종료 코드 0
- 감사 결과: CRITICAL 0 · HIGH 0
- 남은 것: (없으면 "없음")
- 릴리즈 갭 변화: ⚠ → ☑ (또는 변화 없음)
```

3. 사람이 결정할 사항(실전 승격, 체크리스트 추가, 리허설 실행)은
   **결정을 대신하지 말고 물어본다**

### Phase 5: 정리

1. 팀 모드면 `TeamDelete`로 정리
2. `_workspace/`는 **지우지 않는다** (나중에 근거를 되짚기 위해)
3. 문서 갱신이 필요하면 어느 문서에 무엇을 적었는지 명시
   (`CLAUDE.md` / `BACKTEST-DESIGN.md` / `BACKLOG.md` / `SCOPE_GUARDIAN.md`)

## 데이터 전달

| 방식 | 용도 |
|---|---|
| 파일 (`_workspace/{순번}_{에이전트}_{주제}.md`) | 산출물·감사 추적 |
| `TaskCreate`/`TaskUpdate` | 진행 상황·의존성 |
| `SendMessage` | 실시간 지적·재작업 요청 |

```
[리더] → scope-guardian (갭 체크)
           ↓
      strategy-quant ──스펙──▶ trading-implementer ⇄ risk-auditor
           │                          │
           └──────────▶ paper-ops-verifier (증거 검증)
                              ↓
                         [리더: 통합 보고]
```

## 에러 핸들링

| 상황 | 대응 |
|---|---|
| 에이전트 1명 실패 | 1회 재시도. 재실패 시 그 영역 **누락을 보고서에 명시**하고 진행 |
| 빌드/테스트 전부 실패 | 코드 문제로 단정하지 말고 `TRADING_BUILD_DIR` 먼저 확인 |
| 감사에서 CRITICAL 발견 | 즉시 전체 중단. 사용자에게 보고하고 승인 없이 진행하지 않음 |
| 백테스트 30분 초과 | 중간 진행 보고. 부분 결과로 판정하지 않음 |
| 산출물 상충 (판정이 엇갈림) | 삭제하지 않고 **출처를 병기**해 둘 다 남기고 사용자 판단을 구함 |
| 재작업 3회 초과 | 멈추고 원인 가설과 함께 보고 |

## 절대 하지 않는 것

- 갭 체크 없이 구현으로 직행
- 릴리즈 체크리스트 항목을 사용자 승인 없이 추가·삭제
- 실전(`real`) 계좌 전환 — 사람 게이트 G2 없이는 제안조차 결론으로 쓰지 않음
- 실제 주문·강제청산을 대신 실행 (절차만 안내, 실행은 사용자)
- 검증 없이 "완료" 선언
- 요청 범위 밖 코드의 "겸사겸사" 정리

## 테스트 시나리오

### 정상 흐름
1. 사용자: "트레일링 스톱 파라미터를 바꾸고 검증해줘"
2. Phase 1 — 갭 체크 출력, 요청은 "백로그 아님/전략 검증"으로 분류
3. Phase 2 — strategy-quant + trading-implementer + risk-auditor + paper-ops-verifier 소집
4. Phase 3 — 구현 → 감사 통과 → 백테스트 판정표 → 테스트 34/34 통과
5. Phase 4 — 통합 보고 + "실전 승격 아님, 사람 승인 필요" 명시
6. 예상 산출물: `_workspace/` 4개 파일 + 코드 변경 + 판정표

### 에러 흐름
1. Phase 3에서 `risk-auditor`가 CRITICAL(전략이 OrderEngine 직접 호출) 발견
2. 즉시 전체 중단, 리더가 사용자에게 보고
3. 사용자 승인 후 `trading-implementer`가 흐름을 원복
4. 재감사 통과 → `paper-ops-verifier` 재검증 → Phase 4
5. 보고서에 "CRITICAL 1건 발견·해소" 이력을 남긴다

### 후속 흐름
1. 사용자: "아까 그 백테스트, 민감도만 다시 돌려줘"
2. Phase 0 — `_workspace/` 존재 + 부분 수정 → 부분 재실행 판정
3. `strategy-quant`만 재호출, 이전 판정 파일을 읽고 민감도 절만 갱신
4. 나머지 산출물은 그대로 보존
