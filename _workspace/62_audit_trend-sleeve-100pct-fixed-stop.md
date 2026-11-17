# 62 — 감사: A동 100% · −15% 고정 손절 · 1주 매수(상한 1R×3) (2026-11-14, risk-auditor 보고를 리더가 옮겨 적음)

판정: **CRITICAL 0 · HIGH 0 · MEDIUM 6 · LOW 6 — 재시작해 paper에 반영 가능**(조건: 재시작 전 TREND 보유 확인 · 사용자가 M-1·M-2 인지 · 규칙 6/ADR 개정 동반).
실행 증거(감사자): 관련 테스트 5묶음 52/52 통과, 종료 코드 0(별도 빌드 폴더). 리더 추가 확인: `/api/position` = `[]`(보유 0, L-2 해당 없음),
`/api/performance/account` = 총자산 10,000,000 · 전고점 10,088,806 · 문턱 9,281,702 · 여유 718,298원(감사자의 "약 51.8만 원"은 총자산 980만 가정치).
백테스트 무영향: `q62-regress-cap-lab-20261114-1402.log` 6칸이 `q61-…-0847.log`와 전 지표 동일.

| 등급 | 항목 | 위치 | 요약 |
|---|---|---|---|
| MEDIUM | M-1 1주 매수 상한이 실제 손절 손실을 묶지 않음 | `OrderSizingService` | 상한은 ATR×1.0 ≤ 3R, 실제 손실은 주가×15% — 평소 사이징도 4.8~9.6R, 고가 1주는 상한 끝에서 15~22.5R |
| MEDIUM | M-2 계좌 낙폭 가드 8%까지 여유 얇음 | `RiskMonitor`, `BacktestRunner` | 백테스트 MDD 6.7~7.1%는 한도 10%·장 마감 판정·새 출발 기준. paper는 장중 1초 판정·8% |
| MEDIUM | M-3 칸 이름표를 잃는 경로(기존) | `ShadowPortfolioReconciler:119-191`, `FillStateUpdater:274-281`, `TimeCutScheduler:155` | 이름표 없는 포지션은 ATR 전역 배수 손절·당일 타임컷. 나중에 이름표가 채워져도 손절선은 −15%로 안 바뀜 |
| MEDIUM | M-4 종목당 10% 룰이 신규 매수를 못 막음 | `PositionLimitRule:18-28`, `OrderEngine:94` | 수량은 룰 통과 뒤 결정. ATR < 2.5%면 한 종목 10% 초과 |
| MEDIUM | M-5 칸 장부가 실제 손실을 반영 안 함 | `BucketAccountService:37-56` | 결함 5로 `trade_result` 미기록 → 1R 고정. 칸 현금 한도도 실제보다 큼 |
| MEDIUM | M-6 규칙 6·ADR §2.1과 충돌 | CLAUDE.md, yml | 같은 날 CLAUDE.md 규칙 6 · ADR-001 개정으로 해소 |
| LOW | L-1 칸 상한 −12% 사실상 무발동 | `SleeveDrawdownGuard:125-159` | 계좌 가드가 먼저. 배분금 변경 시 최고 기록 재기준은 정상(헛잠금 없음) |
| LOW | L-2 기존 TREND 보유분은 옛 손절 유지 | `ShadowPortfolioReconciler:182` | 보유 0 확인 — 해당 없음 |
| LOW | L-3 체결가 0원 검사 없음 | `StopLossArmer` | 기존 ATR 경로와 같은 성질 |
| LOW | L-4 테스트 공백 | — | 칸 오버라이드로 1주 매수가 켜지는 경로, 이름표 없는 포지션 대체 경로 |
| LOW | L-5 낡은 주석 | yml·로그 | yml 주석은 같은 날 수정. 사이징 로그의 "실제리스크"는 ATR 기준 |
| LOW | L-6 B동 재가동 전제 | `BucketProperties` | 배분금 합계 ≤ 계좌 검사 없음 |

문제없음 확인: 주문 흐름 우회 없음·`RiskEngine` 무수정 · 체결 경로에서 칸 부착 뒤 −15% 장착, 재시작 후에도 유지 · `StopLossMonitor`·트레일 충돌 없음 ·
backtest 무영향(오버라이드는 paper yml에만) · 대시보드 저장값(`app_setting`)이 배분금·칸 오버라이드를 덮어쓸 경로 없음 · 비밀키 없음.
