#!/bin/sh
# 새 세션이 시작될 때 하네스 사용 규칙을 컨텍스트에 주입한다.
# CLAUDE.md만으로는 "갭 체크 먼저"가 자주 누락되므로 훅으로 못 박는다.
#
# 이 환경에는 jq가 없다 — 외부 도구 없이 printf로 JSON 한 줄을 직접 만든다.
# 본문에 큰따옴표(")와 역슬래시(\)를 쓰지 말 것. JSON이 깨진다.
printf '%s' '{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"'
printf '%s' '[하네스 활성 — auto_trading]\n'
printf '%s' '1. 이 프로젝트의 실질적 작업(전략 추가·수정, 백테스트 검증, 리스크 룰 변경, 주문·체결 파이프라인 수정, 운영 신뢰성, 릴리즈 갭 해소)은 trading-orchestrator 스킬로 처리한다. 단순 질문·설명은 하네스 없이 직접 답한다.\n'
printf '%s' '2. 구현에 착수하기 전 SCOPE_GUARDIAN.md를 읽고 [릴리즈 갭 체크]를 먼저 출력한다. 갭 체크 없이 새 기능 구현으로 직행하지 않는다.\n'
printf '%s' '3. 전문 에이전트: strategy-quant(전략·백테스트 판정) / risk-auditor(안전장치 감사) / trading-implementer(구현) / paper-ops-verifier(증거 검증·운영 점검) / scope-guardian(범위 문지기). Agent 호출 시 model 파라미터에 opus를 명시한다.\n'
printf '%s' '4. 빌드·테스트는 spring-build-verify 스킬 절차로만 실행한다. TRADING_BUILD_DIR이 없으면 테스트가 전부 ClassNotFoundException으로 죽는다.\n'
printf '%s' '5. 실전(real) 계좌 전환·강제청산 실행·주문 실행은 사람이 결정하고 사람이 누른다.'
printf '%s\n' '"}}'
