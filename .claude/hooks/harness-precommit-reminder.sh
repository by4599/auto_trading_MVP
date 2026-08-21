#!/bin/sh
# git commit 직전에만 발동하는 안전 점검 리마인더.
# 커밋은 되돌리기 어렵고, 이 저장소는 돈이 걸린 매매 코드를 담고 있다.
# 차단하지 않고 컨텍스트만 주입한다 — 판단은 그때의 맥락에 맡긴다.
#
#
# settings.json의 if 필터는 `&&`로 이어진 복합 명령에서 헐겁게 걸린다(실측).
# 그래서 여기서 한 번 더 거른다 — 실제로 git commit이 아닌 명령이면 조용히 종료.
INPUT=$(cat)
case "$INPUT" in
  *"git commit"*) ;;
  *) exit 0 ;;
esac

# jq 없이 printf로 JSON 한 줄을 만든다. 본문에 큰따옴표/역슬래시 금지.
printf '%s' '{"hookSpecificOutput":{"hookEventName":"PreToolUse","additionalContext":"'
printf '%s' '[커밋 전 점검 — 하네스]\n'
printf '%s' 'src/ 아래 매매 코드가 이번 커밋에 포함된다면 커밋 전에 확인한다: (1) risk-rule-audit 스킬로 흐름 우회·리스크 룰·프로필 격리·비밀키 감사, (2) spring-build-verify 스킬로 테스트를 방금 실행한 증거(통과 건수·종료 코드). 둘 중 하나라도 없으면 커밋 전에 사용자에게 알린다.\n'
printf '%s' '문서·설정만 바뀐 커밋이면 그대로 진행해도 된다.'
printf '%s\n' '"}}'
