# 15_audit — 마감 손익 알림 이월 (notified_at + 09:05 catch-up)

감사자: `risk-auditor` (2026-09-15) · 코드 미수정 · 대상 4파일 (HEAD `a956375`)
※ 감사관 도구에 Write가 없어 리더가 옮겨 적고, 후속 조치 결과를 덧붙였다.

## 판정: CRITICAL 0 · HIGH 0 · MEDIUM 2 · LOW 4 · 판단보류 2 → 배포 차단 사유 없음

## §0 이월 상태
`5_audit` HIGH ✅해소 유지 · `9_audit` 이월 없음 ·
**`12_audit` HIGH(`ConsecutiveLossRule` paper 무발동) ❌미해소 — 이번 범위 밖(risk 증분 0파일)** ·
**`13_audit` MEDIUM M-1(전송 창 조용한 소실) ✅해소 확인** · `13_audit` LOW 3건 그대로 ·
`13_audit` §9 판단보류(real 프로필 부재) 그대로.

## §1 원래 결함은 막혔다 — 체인까지 실증

`sendAndMark`가 전송 **직전에** 창을 다시 본다(`:209`). 창 밖이면 미전송(`:212`) ·
도장 미기록 · `log.warn`(`:210-211`). 세 조건 충족.

**테스트가 진짜 그 시나리오를 만든다** (감사관이 코드로 추적):

| 단계 | 위치 | 시각 |
|---|---|---|
| `SteppingClock` 시작 | `DailyPnlRecorderTest:252-253` | 15:29:58 |
| 첫 게이트 통과 | `DailyPnlRecorder:152` → `MarketCalendarService:70` | 15:29:58 ✅ |
| 잔고 조회 5초 소모 | `DailyPnlRecorderTest:258-261` | → 15:30:03 |
| 전송 직전 탈락 | `DailyPnlRecorder:209` | 15:30:03 ❌ |

**이월 체인 실증** (감사관이 스크래치에서 두 반쪽을 직접 이어 붙임 — 저장소에 이 체인 테스트는 없다):
```
CHAIN step1 sent=0 notifiedAt=null closed=true           ← 09-14 15:29 창 이탈
CHAIN msg= 📒 [지난 거래일(2026-09-14) 마감분] 그날 120,000원 벌었습니다 (1.20%)
CHAIN step2 notifiedAt=2026-09-15T09:05                  ← 09-15 09:05 실제 발송
```

## §2 지적 사항과 조치

| 심각도 | 항목 | 위치 | 조치 |
|---|---|---|---|
| MEDIUM | **M-1 예외 누출** | `DailyPnlRecorder:214-222` | 도장 `save()`가 try 밖이라 DB 장애 시 예외가 `recordCloseFor` 밖으로 나가고 이월 루프가 나머지를 버린다. 프로브 실측 `PROBE1 thrown=RuntimeException` / `PROBE2 thrown=DB down`. **13_audit §2 격리 계약 약화** → ✅ **해소 (리더, 2026-09-15)**: `markNotified`+`save()`를 try 안으로 옮기고 회귀 테스트 2건 신설(도장 저장 실패가 안 샌다 / 이월 중 1건 실패해도 나머지는 계속) |
| MEDIUM | **M-2 도장 의미** | `:221` + `TelegramNotifier:47-66` | `sendCritical`이 예외·미설정·창밖을 전부 삼켜 **사실상 던지지 않는다** → 전달 실패해도 `notified_at`이 찍힌다. 컬럼의 실제 의미는 "전달"이 아니라 **"창 안에서 예외 없이 send()를 불렀다"**. 이월 장치는 **창 이탈만** 복구하고 전달 실패는 복구 못 한다 → **미해소**. 근본 해소는 `NotificationService`가 성공 여부를 돌려줘야 하는데 인터페이스 변경(규칙 4)이라 범위 밖. **javadoc에 뜻을 명시**(`:205-208`)하는 선에서 기록 |
| LOW | L-1 도착 순서 | `:133-141` | 7건 밀리면 최근 5건이 먼저 → 도착 순서 ≠ 날짜 순서. 용인 |
| LOW | L-2 창 판정 비원자 | `:209` ↔ `TelegramNotifier:52` | 두 판정 사이 마이크로초에 15:30을 넘기면 도장만 찍힘. M-1과 같은 종류의 10⁶분의 1 축소판 |
| LOW | L-3 기동 이월 없음 | `:99-102` | 09:05에 앱이 안 떠 있으면 그 아침 통째 스킵 → 다음 거래일까지 지연. 실측 기동 08:30/08:44이나 01:16 사례 있음 |
| LOW | L-4 늦개장일 | `market-calendar.yml` 2026-11-19 10:00 개장 | 그날 09:05 이월은 스킵(프로브 `LATEOPEN notifiedAt=null`). 하루 지연될 뿐 손실 없으나 미문서화 |

## §3 나머지 판정

- **이월이 원장을 오염시키지 않는다** ✅ — `fetchBalance()`는 `:169` 한 곳(`recordCloseFor` 안)뿐,
  이월 경로에 잔고 호출 0건. `recordClose(...)`도 `:180` 한 곳. `markNotified`는 `notifiedAt`만 쓴다
- **누락 없음** ✅ — `trimToRecent`(`:133-142`)는 `subList(cut, size)`로 **최근 5건을 보내고 오래된 것을 미룬다**.
  프로브 `PROBE3 총 발송=7 · 전부 도장=OK`. 하루 최대 +1 유입 vs 5 유출 → 영구 기아 경로 없음
- **09:05 타당** ✅ — 단 근거는 "경합이 낮아서"가 아니다(아침도 한가하지 않다: 09-10 09:00:03~10.9에
  KIS HTTP 500 재시도로 11초 점유). **진짜 근거는 마감까지의 거리** — 15:29은 60초, 09:05는 **6시간 25분**.
  13_audit 최악 지연(+321초)을 먹여도 09:10:21로 창 한복판. 약 385배 여유.
  이월은 KIS를 안 부르므로 연속 실패 카운터에 쌓일 수 없다
- **매매 경로 무변경** ✅ — `git diff --name-only -- risk/order/strategy/signal/bucket/backtest` = **0파일**
- **스키마 안전** ✅ — `notified_at` nullable. H2에 실제 `alter table ... add column` 시뮬 성공.
  백테스트 4중 격리(@Profile · `BacktestPositionManager`가 `end_equity`를 안 씀 · 별도 DB · 매 실행 전량 삭제).
  **첫 배포 폭주 없음**: 09월 로그의 `[일별손익]` 93줄이 **전부 `[Test worker]`**, 운영 발화 0건
- **파생 쿼리 파싱** ✅ Red-Green 실증 — 이름을 존재하지 않는 속성으로 바꾸니
  `PropertyReferenceException` → `@DataJpaTest` 26건 FAILED. 즉 645개 테스트가 이미 이름을 검증한다
- **프로필 격리·비밀키** ✅ — `@Profile("paper")` 유지, 신규 하드코딩 0건

## §4 검증

```
[감사관 직접, 별도 빌드 경로]
gradle clean test → 종료 코드 0 · classes=95 tests=645 failures=0 errors=0
기준선 637 → 645 (신규 8) — 보고와 일치

[리더 — M-1 수정 후, 별도 빌드 경로]
gradle clean test → 종료 코드 0 · classes=95 tests=647 failures=0 errors=0
→ 645 + 신규 2 (M-1 회귀). 판정: 통과
```
안전 조치: 라이브 앱 **PID 16216 생존** · `C:/Users/SAMSUNG/auto_trading-build` mtime 08:48:24 불변
· `trading-db` 미접근 · 변이·프로브는 스크래치 사본에서만.

## §5 확인하지 않은 것 (통과로 적지 않는다)

1. **스프링 런타임 배선 미확인** — `@SpringBootTest`가 없다. `09:05` 크론은 운영에서 한 번도
   발화한 적이 없다 → **재기동 후 첫 아침에 실제 도착 확인 필요**
2. **라이브 `trading-db` 미조회**(지시). "적체 없음"은 로그+커밋 시각 기반 **추론**.
   이월 쿼리에 **하한 날짜가 없다** — 마감·미도장 행은 영구 대상(현재는 무해)
3. **텔레그램 실제 도달률 미측정** — M-2와 직결
4. `ConsecutiveLossRule` paper 무발동(12_audit HIGH) 미해소
5. 15:28 최종스윕 실매도 소요 미관측

## §6 결론

```
배포 차단 사유 없음.
13_audit M-1(창 경계 조용한 소실)은 해소됐고 이월까지 체인으로 실증됐다.
M-1(예외 누출)은 이 커밋에서 해소, M-2(도장 의미)는 문서화로 기록만.
→ 사용자에게 "이제 알림이 반드시 온다"고 단언하지 말 것:
   텔레그램이 거부해도 원장은 '보냄'으로 기록된다.
```
