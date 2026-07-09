# 추천 → 검토 → 실제 투자 파이프라인 실행 설계

> [투자 판단 방법론](INVESTMENT-METHODOLOGY.md) §5 단계표(Phase 2~3d)의 **실행 설계 문서**다.
> 현재 대시보드의 "뉴스 기반 투자 추천"(표시 전용)이 어떤 관문들을 거쳐
> 실제 주문까지 이어지는지를 컴포넌트 수준으로 정의한다.
> 상위 원칙: 방법론 원칙 ②(백테스트 없는 규칙 금지)·④(LLM은 후보 생성까지만),
> ADR-001(흐름 고정: Strategy → Signal → RiskEngine → OrderEngine).

비유하면: 지금의 추천은 **"신문 읽고 후보 메모"** 단계다. 실제 투자까지 가려면
후보 메모 → 신용 조회(재무 검토) → 과거 전과 조회(이벤트 통계 검증) → 대출 심사(리스크 관문)
→ 집행(주문)의 5단 심사를 전부 통과해야 하며, **어느 단계도 건너뛸 수 없다.**

---

## 0. 파이프라인 전체 그림

```
[S0 수집]      뉴스 RSS 4피드 (현행 ✅)  +  DART 공시 (신규 — 뉴스보다 상위 정보원)
    │
[S1 구조화]    EventClassifier: 키워드(현행 ✅) + LLM 비동기 배치(신규)
    │            → 이벤트 = {유형, 방향, 강도, 대상, 지속성}
    │
[S2 후보 검토] "투자 가능성 분석" ── 탈락시키는 단계
    │            ValueChainMapper(이벤트→수혜 종목군) → FinancialFilter(ROE·부채·유동성)
    │            → 대시보드 "검토된 추천" 표시 (여기까지는 사람 참고용 — 현행 추천 카드의 진화형)
    │
[S3 통계 검증] EventStatsBacktester(B-4): 유형별 과거 D+1~D+20 반응 통계
    │            → 사람이 웹 UI에서 이벤트 유형을 승격 (event_type_registry 화이트리스트)
    │            → 미검증 유형은 주문 불가, OpportunityCostLogger처럼 기록만
    │
[S4 신호 생성] EventDrivenStrategy (Strategy 구현체)
    │            승격된 유형 × 검토 통과 종목 × 매매 유니버스 소속 → Signal 생성만
    │
[S5 리스크 관문] RiskEngine — 기존 7룰 + RiskRewardRule(신규: 기대수익÷손절폭 ≥ 2.0)
    │
[S6 집행]      OrderEngine → KIS 주문
                 R 사이징(1% 룰 역산) · ATR 손절 · 지정가 분할 · 15:15 타임컷(✅)
```

**게이트 원칙**: S2까지는 "보여주기"(사람 참고), S3부터가 "쏘기"(자동 매매) 영역이다.
S3의 승격 없이는 S4가 신호를 만들지 않으므로, 파이프라인을 다 만들어도
검증 전에는 현재처럼 추천 표시만 동작한다 — **안전한 기본값(fail-safe default)**.

---

## 1. 현재 자산 vs 갭

| 구분 | 이미 있음 ✅ | 없음 — 이 설계의 구현 대상 |
|---|---|---|
| 수집 | 뉴스 RSS 4피드, 30분 주기, 워치리스트 매칭 | `DartDisclosureService` (공시 수집) |
| 분류 | 키워드 감성 (70~80%) | `EventClassifier` (LLM 배치, 택소노미 구조화) |
| 검토 | 뉴스 감성 점수·판정 (RecommendationService) | `ValueChainMapper`, `FinancialFilter` |
| 검증 | — | 백테스트 엔진 B-1~B-3 + 이벤트 확장 B-4, `event_type_registry` |
| 신호 | VolatilityBreakoutStrategy (당일 돌파) | `EventDrivenStrategy` |
| 리스크 | 7룰 전부 활성 (Gate 1~3 완료) | `RiskRewardRule` (Phase 3d) |
| 집행 | 시장가 1주, 타임컷, 강제청산 실행부 | ATR 손절, R 사이징, 지정가 분할 (Phase 2 잔여) |

---

## 2. 단계별 상세 설계

### S0. DART 공시 수집 — `research.DartDisclosureService` (Phase 3a)

- DART Open API(무료, 인증키 필요)로 워치리스트 종목의 공시를 30분 주기 폴링.
  수집 유형: 단일판매·공급계약, 실적(잠정 포함), 유상증자, 자사주, 최대주주 변경, 소송.
- 저장: `disclosure_item` 테이블 (news_item과 동형 + `report_type`, `receipt_no`).
- 뉴스와의 관계: 방법론 §1 — 공시가 1순위, 뉴스는 6순위. 같은 사건이 양쪽에서 오면
  공시 타임스탬프를 기준으로 삼는다 (백테스트 선견 편향 방지의 기준점).

### S1. 이벤트 구조화 — `research.EventClassifier` (Phase 3a)

- 입력: news_item + disclosure_item. 출력: `event` 테이블
  `{유형(taxonomy), 방향(+/-), 강도(1~3), 대상(종목/섹터), 지속성(단발/구조적), 원문 ID}`.
- 2단 분류: ① 키워드 룰(현행 확장 — 공시 report_type은 유형이 자명해 룰만으로 충분)
  ② 뉴스 제목·요약의 LLM 배치 분류 (Claude API, **비동기 — 1초 매매 루프와 완전 분리**).
- **LLM 가드레일 (방법론 원칙 ④, 협상 불가)**:
  - LLM 입력(뉴스 본문)은 신뢰할 수 없는 데이터로 취급 — 본문 안의 지시문은 무시하는
    시스템 프롬프트 고정, 출력은 JSON 스키마 검증(zod식 화이트리스트) 후에만 저장.
  - LLM 출력은 event 행 생성까지만. **event → 주문의 경로에는 LLM이 존재하지 않는다.**
  - 분류 실패/스키마 불일치는 NEUTRAL·미분류로 폴백 (안전한 기본값).

### S2. 후보 검토 (투자 가능성 분석) — 탈락 필터 (Phase 3b)

**`research.ValueChainMapper`** — 이벤트 유형 → 수혜 종목군 매핑:
- `value_chain_map` 테이블: `{이벤트 유형, 섹터, 종목코드, 수혜 방향, 레버리지 순위}`.
  사전 구축은 수동(사람) + LLM 초안 보조, **테이블 수정은 사람만** (웹 UI).
- 예: "전기차 보조금 확대" → 2차전지 밸류체인 → 셀/소재/장비 종목 후보.

**`research.FinancialFilter`** — 방법론 §2.2 그대로, 발굴이 아닌 **탈락** 용도:

| 필터 | 기준 (잠정 — 설정 파일로 관리) | 데이터 소스 |
|---|---|---|
| 수익성 | ROE > 8%, 영업이익 흑자 | DART 재무제표 API (분기 갱신 캐시) |
| 안정성 | 부채비율 < 200% | 〃 |
| 유동성 | 일평균 거래대금 > 50억 (슬리피지 방어) | KIS 일봉 API |
| 과열 | 섹터 평균 대비 PER 상위 이탈 시 감점 | KIS + DART |

- 출력: 추천 카드 확장 — 현행 `{점수, 판정}`에 `{검토: PASS/FAIL, 탈락 사유}` 추가.
  **대시보드는 여기까지 항상 표시** (S3 이후 자동화가 꺼져 있어도 사람이 참고 가능).

### S3. 통계 검증과 승격 — 자동화의 관문 (Phase 3c, 백테스트 B-4)

- `EventStatsBacktester`: 과거 event × candle_history로 유형별 D+1~D+20 수익률 분포 산출.
  기대수익은 평균이 아니라 **중앙값과 하위 25%** 채택 ([백테스트 §3.2](BACKTEST-DESIGN.md)).
- `event_type_registry` 테이블: `{유형, 상태(RECORDED/CANDIDATE/PROMOTED), 기대수익 통계, 승격일}`.
  - 승격 조건: 표본 ≥ 30건, 하위 25% 수익률 > 왕복 비용(0.3~0.5%), 검증 구간 PF ≥ 1.3.
  - **승격 행위는 사람이 웹 UI 버튼으로** — 자동 승격 금지. 강등은 자동
    (거버넌스의 괴리 판정 재사용).
- 미승격 유형의 이벤트는 `OpportunityCostLogger` 패턴으로 "만약 진입했다면" 기록만 남긴다
  — 이 기록 자체가 표본이 되어 승격 심사 데이터로 쌓인다.

### S4. 신호 생성 — `strategy.EventDrivenStrategy` (Phase 3d)

- `Strategy` 인터페이스 구현체 — 기존 파이프라인에 플러그인 (ADR 흐름 무수정):
  ```
  evaluate(): PROMOTED 유형의 미소비 event × S2 검토 PASS × 매매 유니버스 소속
              → Signal.buy(stockCode, "EventDriven-{유형}") 반환만. 주문 금지.
  ```
- **매매 유니버스 분리**: 리서치 워치리스트(뉴스 수집용, 현행)와 매매 유니버스
  (`trading_universe` 테이블)는 별개다. 유니버스 편입은 방법론 §2.5의 조건 충족 +
  **사람 승인**, 상한 20종목. TradingScheduler의 `WATCH_LIST` 하드코딩을 이 테이블로 교체.
- Signal 확장: `Signal`에 `eventId`(nullable) 추가 — S5의 `RiskRewardRule`이 이벤트의
  기대수익 통계를 조회하는 키. 당일 돌파 신호는 eventId=null (기존 동작 무변화).

### S5. 리스크 관문 — `risk.RiskRewardRule` (Phase 3d)

- `RiskRule` 구현 + `@Component` — RiskEngine 무수정 자동 주입 (아키텍처 규칙 3).
- 검사: `eventId` 있는 BUY에 한해 `기대수익(S3 통계의 중앙값) ÷ 손절폭(ATR×k) ≥ 2.0`
  미달 시 거부. **당일 돌파 신호(eventId=null)는 통과** — 방법론 §4.4의 층위 분리:
  당일 청산 전략에 건별 손익비를 흉내 내면 전략이 변질된다.
- 기존 7룰은 그대로 앞단에서 작동 (중복 매수·비중 10%·5종목·시간·일일손실·MDD·연속손실).

### S6. 집행 — Phase 2 잔여 과제가 선행 조건

방법론 설계 원칙 ① "출구가 입구보다 먼저다". S4~S5를 켜기 전에 완성해야 한다:

| 항목 | 설계 | 근거 |
|---|---|---|
| ATR 손절 | 체결가 − ATR(14)×k (k=1.5~2.0, B-3 검증) — `RiskMonitor`에 손절 감시 추가 | §4.1 |
| R 사이징 | 수량 = (계좌×1%) ÷ 손절폭, 내림, 왜곡 ±20% 초과 시 스킵. `ORD_QTY=1` 대체 | §4.3 |
| 지정가 분할 | Price Jitter (ADR-001 2.5) — 시장가 폐지 | §4.3 |
| 보유 기간 | 이벤트 포지션은 타임컷 예외 검토 필요 — D+N 보유가 통계의 전제. **v1은 예외 없이 당일 청산 유지**, 스윙 보유는 S3 통계가 "익일 이후 수익 집중"을 증명할 때만 별도 설계 | §4.5 |

---

## 3. 사람 개입 지점 (수동 게이트 3 + 1)

| # | 게이트 | 도구 | 자동화 금지 이유 |
|---|---|---|---|
| G1 | 매매 유니버스 편입 | 웹 UI 승인 버튼 | 관리종목·유동성 함정은 정량 필터가 놓친다 |
| G2 | 이벤트 유형 승격 | 웹 UI 승격 버튼 (통계 첨부) | 원칙 ② — 통계가 좋아 보여도 레짐 의존일 수 있음 |
| G3 | 실전 계좌 전환 | 수동 (모의 30건 + 거버넌스 §5) | TRADING-RULES-AUDIT CRITICAL 완전 해소 후 |
| G0 | value_chain_map 수정 | 웹 UI (LLM은 초안만) | 매핑 오류는 엉뚱한 종목 매수로 직결 |

**긴급 차단기(이미 보유)**: 대시보드 중지 버튼, DailyLossRule(-3%/-5%), GlobalEquityStopRule
(MDD 10%), LiquidationService + KisBrokerageApiClient(강제청산 실행) — 파이프라인이
어떤 오판을 해도 계좌 단위 손실은 이 층이 상한을 건다.

---

## 4. 구현 순서 — 게이트 조건이 걸린 스프린트

| 순서 | 작업 | 완료 판정 (증거 기반) |
|---|---|---|
| P2-A | ATR 손절 + R 사이징 + 지정가 분할 + Clock 주입(F-8) | 🟡 **코드 완료 (2026-07-08)** — ATR 손절·R 사이징·Clock 구현 (테스트 125/125). 지정가 분할은 ADR-001 3장 파라미터 결정 대기. 남은 판정: 모의계좌에서 손절·수량 역산 체결 확인 |
| B-1~2 | candle_history 적재 + 백테스트 3구현체 | `--profile backtest` 실행 성공 |
| B-3 | **현행 K=0.5 소급 검증** | 채택/기각 판정 문서 — 기각 시 Phase 3 착수 전 전략 교체 |
| 3a | DART 수집 + EventClassifier(키워드→LLM) | event 테이블 적재, 분류 정확도 스팟체크 |
| 3b | ValueChainMapper + FinancialFilter + 추천 카드 확장 | 대시보드 "검토된 추천" 표시 |
| B-4/3c | 이벤트 백테스트 + event_type_registry + 승격 UI | 유형별 통계 테이블, 승격 0건 상태로 출시 |
| 3d | EventDrivenStrategy + RiskRewardRule + Signal.eventId | 모의계좌에서 승격 유형 1건의 전 파이프라인 통과 |
| G3 | 모의 30건 축적 → 실전 축소 자금 | 거버넌스 승격 절차 |

**의존의 이유**: 3d를 먼저 만들면 "검증 안 된 신호를 쏘는 총"이 생긴다. B-3/B-4가
먼저면 총이 완성될 때 이미 탄약(검증된 유형)의 진위가 가려져 있다.

---

## 5. 명시적 비목표 (이 설계가 하지 않는 것)

- **LLM이 주문을 트리거하는 경로** — 어떤 형태로도 만들지 않는다 (원칙 ④).
- **리딩방·유튜브 추천의 정보원 채택** — 방법론 §2.5 입장 유지.
- **감성 점수만으로의 진입** — 현행 추천 점수(키워드 감성)는 S2 표시까지만 쓰이고,
  S4의 신호 조건에는 포함되지 않는다 (S3 통계 검증을 통과한 이벤트 유형만 신호).
- **당일 돌파 전략에 건별 손익비 적용** — §4.4 층위 분리 위반.
- **EPS 추정·DCF 자동화** — 컨센서스 변화율 수집으로 대체 (Phase 4 이후 검토).

## 관련 문서

- [INVESTMENT-METHODOLOGY.md](INVESTMENT-METHODOLOGY.md) — 상위 방법론 (이 문서의 §번호 인용 출처)
- [BACKTEST-DESIGN.md](BACKTEST-DESIGN.md) — B-1~B-4 백테스트 인프라
- [PERFORMANCE-GOVERNANCE.md](PERFORMANCE-GOVERNANCE.md) — 승격/강등 판정
- [ADR-001](../ADR-001-multi-sleeve-risk-architecture_1.md) — 자금 배분·청산 아키텍처
