# 22_impl — 대시보드 화면을 계좌 기준으로 갈아끼우고 「진단」 탭을 추가

> 작업 2026-09-21 KST · 브랜치 `backtest/regime-filter-and-validation`
> 선행: `_workspace/19_impl_account-performance.md`(백엔드 API) · `20_audit_account-performance.md`
> **프런트엔드 전용 — 자바 코드 0줄 변경** (`git diff --stat`으로 확인, §7)

## 0. 한 줄

**화면이 읽는 숫자의 출처를 오염된 거래기록(-5,014,000원)에서 계좌 잔고 원장(-212,040원)으로
옮기고, 계좌를 멈춰 세우는 숫자(전고점·낙폭·멈춤까지 남은 돈)를 「진단」 탭에 올렸다.**
옛 숫자는 지우지 않고 접힌 칸으로 내려 응답의 경고 문구와 함께 남겼다.

브라우저 없이 돌린 화면 로직 스모크 테스트 **30건 전부 통과 · 종료 코드 0** (§6).
**실제 브라우저 렌더링은 확인하지 못했다 — 배포 전이다** (§8).

---

## 1. 바꾼 / 만든 파일 (300줄 상한 대비)

### 새로 만든 것

| 파일 | 역할 | 줄 | 상한 |
|---|---|---|---|
| `static/js/chart-svg.js` | 손으로 그리는 SVG 차트. `buildLineChart`(값 범위에 맞춘 y축) · `buildBarChart`(0선 위아래 색 구분) + 격자·0기준선·x라벨·마우스오버 툴팁 | 203 | OK |
| `static/js/ui-parts.js` | 실적·진단이 함께 쓰는 화면 조각 — `statGrid` · `buildTable` · `chipRow` · `emptyState` · `noticeBox` · `subHeading` · `showFetchError` · `shortDate` | 139 | OK |
| `static/js/tab-diagnostics.js` | 진단 탭 4칸 (낙폭 감시 · 미체결 · 가동일 · 체결 내역 전체) | 249 | OK |

> `ui-parts.js`를 따로 뺀 이유: 요약 칸과 표를 만드는 코드가 **4곳에서 같은 모양**으로 반복된다
> (실적 계좌 · 실적 옛숫자 · 진단 미체결 · 진단 체결내역). 베껴 쓰면 `tab-performance.js`와
> `tab-diagnostics.js`가 각각 300줄을 넘긴다. 추상화 기준(3회 이상 반복)을 넘겼다.

### 고친 것

| 파일 | 전 | 후 | 바꾼 것 |
|---|---|---|---|
| `static/js/tab-performance.js` | 267 | **233** | 계좌 기준으로 재작성. 차트 코드는 `chart-svg.js`로 이사, 옛 `/api/performance` 화면은 접힌 칸으로 이동(삭제 아님) |
| `static/index.html` | 275 | **315** | 「진단」 탭 1개 추가 · 실적 탭 구조 교체 · 스크립트 3개 추가 · 캐시 버스터 `?v=6` → `?v=7`. **상한 초과 — §2에 분리 계획** |
| `static/css/app.css` | 520 | 569 | 새 클래스만 **끝에 49줄 추가**(기존 줄 0줄 수정): `.stat-grid/.stat-box/.stat-label/.stat-value/.stat-note` · `.sub-hdr` · `.chart-wrap` · `.btn-row` · `.wl-del.active/:disabled` · `.msg.warn` · `.badge-sm.warn` · `details.card > summary` |
| `static/js/core.js` | 522 | 522 | **1줄만** — `TAB_IDS`에 `'diag'` 추가. 탭 라우터가 여기 있어 피할 수 없다. 기능 코드는 얹지 않았다 |
| `static/js/tab-home.js` | 483 | 483 | **1줄만** — `실현손익: Sprint 3 예정` → `realizedPnl` 실값 표시 |

`core.js`·`tab-home.js` 실제 diff (각 1줄, 그 외 0줄):

```diff
-const TAB_IDS = ['home', 'perf', 'positions', 'review'];
+const TAB_IDS = ['home', 'perf', 'diag', 'positions', 'review'];
-      `실현손익: Sprint 3 예정`,
+      `실현손익: ${SIGN(d.realizedPnl)}${KRW(d.realizedPnl)}`,
```

---

## 2. `index.html` 315줄 — 분리 계획 (지시대로 표로 먼저)

**이번에는 실행하지 않았다.** 아래 두 안은 모두 *실행 중 동작이 바뀌는* 변경이라
브라우저로 확인할 수 없는 지금(배포 전) 손대면 검증 없이 위험만 얹는다. 리더 판단용으로 남긴다.

| 안 | 떼어낼 덩어리 | 줄 | 후 | 방식 | 위험 |
|---|---|---|---|---|---|
| **A (권장)** | 설정 모달 전체 (`#overlay` 블록) | 113 | **202** | `settings-modal.html` 조각 파일 + 기동 시 `fetch`로 `<body>`에 끼워 넣기 | 모달 DOM이 늦게 생긴다. `core.js`가 미설정 계좌에서 `DOMContentLoaded` 직후 `openSettings()`를 부르므로 **삽입 완료를 기다리는 순서 보장이 필요**(지금은 항상 존재) |
| B | 탭 패널 4개(홈 제외)의 카드 껍데기 | 약 70 | 약 245 | 각 `tab-*.js`가 카드 껍데기까지 만들게 함 | 화면 구조가 HTML에서 안 보인다. JS가 죽으면 빈 화면(지금은 "불러오는 중..." 문구라도 남음) |
| C | 아무것도 안 함 | — | 315 | 15줄 초과를 감수 | 없음 — 대신 규칙 위반 상태 유지 |

> 참고: `css/app.css`(569) · `js/core.js`(522) · `js/tab-home.js`(483)는 **이번 작업 이전부터** 상한 초과다.
> 지시대로 **여기에 기능을 얹지 않았고**, 이번 작업으로 새로 생긴 파일은 전부 300줄 미만이다.

---

## 3. 화면 요소 → API 필드 대응표

### 실적 탭 — 위쪽 (정본) · `GET /api/performance/account?days=365` · 60초

| 화면 | 필드 |
|---|---|
| 「번 돈」 + 밑줄 "처음 …" | `cumulativePnl` + `initialEquity` |
| 「수익률」 | `cumulativeReturnPercent` |
| 「지금 낙폭」 + 밑줄 | `currentDrawdownPercent` + `peakEquity` · `mddLimitPercent` |
| 「가장 컸던 낙폭」 | `maxDrawdownPercent` |
| 「지금 총자산」 + 밑줄 | `currentEquity` + `closedDays` |
| 총자산 곡선(선 그래프) | `series[].date` · `series[].equity` (툴팁에 `initialEquity` 대비 차이) |
| 하루 손익(막대) | `daily[]` 중 `closed=true`인 행의 `netPnl` (툴팁 `endEquity` · `netPnlPercent`) |
| 날짜별 표 | `daily[].date/closed/startEquity/endEquity/netPnl/netPnlPercent` |

### 실적 탭 — 아래쪽 접힌 칸 (참고) · `GET /api/performance?period=&days=90` · 탭 진입 시 1회

| 화면 | 필드 |
|---|---|
| 노란 경고 상자 | **`source` 원문 그대로** + 앞줄 "아래 숫자는 믿지 마세요. 위쪽 계좌 숫자가 정답입니다." |
| 일별/주별/월별 버튼 | `period` 파라미터 |
| 누적 손익 · 승률 · 평균 이익 · 평균 손실 | `summary.totalPnl/winRate/avgWin/avgLoss` |
| 누적 곡선 · 표 | `buckets[].label/pnl/cumPnl/trades/wins` |
| 백필 버튼 | `POST /api/performance/backfill` (기존 동작 그대로 — 지우지 않았다) |

### 진단 탭

| 칸 | API | 주기 | 필드 |
|---|---|---|---|
| 낙폭 감시 | `GET /api/performance/account?days=365` | 60초 | `peakEquity` · `peakUnverified` · `currentEquity` · `currentDrawdownPercent` · `mddLimitPercent` · `forcedStopThreshold` · `roomToThreshold` |
| 미체결 주문 | `GET /api/orders/open` | 10초 | `requestedAt` · `stockCode` · `side` · `quantity` · `filledQty` · `pendingQty` · `status` · `orderNo` |
| 연속 가동일 | `GET /api/trading/run-streak` | 60초 | `streakDays` · `goalDays` · `achieved` · `lastRecordedDate` |
| 체결 내역 전체 | `GET /api/orders/filled?days=&page=&size=20` | 진입 시 1회 + 버튼 | `requestedAt` · `stockCode` · `side` · `filledQty` · `filledPrice` · `status` |

- **`bucket`(지갑 칸)은 화면에 쓰지 않았다** — 미체결 표가 8열이 되면 좁은 화면에서 깨진다. API는 주고 있으니 필요하면 나중에 붙이면 된다.
- 폴링은 전부 기존 `core.js`의 `Poll.register`를 쓴다 — **새 타이머를 만들지 않았다**
  (활성 탭만 돌고 브라우저를 다른 창으로 옮기면 멈추는 구조 그대로).
- 체결 내역만 `ms=0`(진입 시 1회)이다. 60초마다 다시 부르면 **사용자가 넘겨 둔 쪽·기간이 초기화된다.**

### 진단 탭의 필수 경고 문구 (지시 그대로)

```
이 숫자는 「앱이 켜져 있었는지」만 셉니다. 매매를 했는지는 보지 않습니다.
```

### 아직 API가 없어 **비워 둔 것**

모드 전환 이력 · 매수 차단 이력 · 종목별 성적. **자리도 만들지 않았고 가짜 숫자도 넣지 않았다.**
(빈 카드를 미리 두면 "고장 난 화면"으로 보인다 — API가 생길 때 카드째 추가하는 편이 정직하다.)

---

## 4. null · 빈 배열 · 실패를 각각 어떻게 다뤘나

| 상황 | 처리 | 근거 |
|---|---|---|
| `daily[].netPnl == null` (마감 기록 없는 날 · 주말) | **막대 그래프에서 제외**(`filter(r => r.closed && r.netPnl != null)`) · 표에는 행을 남기고 「마감 기록 없음」 | 0으로 그리면 "그날은 본전"이라는 거짓말이 된다 |
| `endEquity` · `netPnlPercent == null` | 표에서 `—` (회색) | 위와 같음 |
| 금액 · 퍼센트가 null | `core.js`의 `KRW(null)` · `PCT(null)`이 이미 `—`를 준다 — **포매터를 새로 만들지 않았다** | 지시 6 |
| `series` / `daily` 빈 배열 | 차트 자리에 "총자산 기록이 아직 없습니다" 안내 (차트를 안 그린다) | `chart-svg.js`가 길이 0이면 안내문을 돌려준다 |
| `currentEquity == null` (기록 자체가 없음) | "아직 계좌 기록이 없습니다 — 하루가 마감되면 쌓이기 시작합니다" | |
| `/api/orders/open` 빈 배열 | "없음 — 지금 시장에 걸려 있는 주문이 없습니다" | 지시 |
| `/api/orders/filled` 빈 배열 | 1쪽이면 "이 기간에는 체결 내역이 없습니다", 2쪽 이상이면 "더 볼 내역이 없습니다 — 「이전」을 누르세요" | |
| **첫 조회 실패**(HTTP 오류 · paper 아님) | 그 칸에만 "…불러오지 못했습니다" 안내 | 화면 전체가 멈추지 않게 칸마다 따로 처리 |
| **이미 그린 뒤 실패** | **옛 숫자를 그대로 둔다**(`el.dataset.loaded === '1'`이면 건드리지 않음) | 잠깐 끊겼다고 금액이 사라지면 더 불안하다. 기존 `catch { /* 유지 */ }` 관례와 같은 뜻 |
| 쪽 넘김(총건수 없음) | 받은 건수 < `size`면 「다음」 버튼을 잠근다 | API에 총건수 필드가 없다(19_impl §2) |

---

## 5. 캐시 버스터

**올렸다.** `index.html`의 `?v=6` → **`?v=7`** — CSS 1개 + JS 8개 = **총 9곳, `v=6` 잔존 0곳**
(새 파일 `chart-svg.js` · `ui-parts.js` · `tab-diagnostics.js`도 `?v=7`로 넣었다).

```
v=6 남은 곳: 0 | v=7: 9
```

---

## 6. 검증 — 이번에 실제로 돌린 것

### ① 문법 검사 (node 24.15.0)

```
js/*.js 8개 각각 node --check              → 전부 오류 없음
index.html 순서대로 이어붙여 node --check  → 종료 코드 0
전역 const/let/function 중복 이름          → 없음
```

> 이어붙여 검사한 이유: 이 프로젝트의 스크립트는 모듈이 아니라 **전역 스코프를 공유**한다.
> `tab-performance.js`에 있던 `SVG_NS` · `svgEl`을 `chart-svg.js`로 옮길 때 한쪽을 지우지 않으면
> **`const` 재선언으로 페이지 전체가 죽는다.** 그게 안 일어남을 확인한 것이다.

### ② 화면 로직 스모크 테스트 — 30건 전부 통과

브라우저가 없으므로 임시 폴더에 **최소 DOM 흉내**(약 40줄)를 만들어 실제 `tab-*.js`를
그대로 실행하고, 과제서에 적힌 **진짜 응답값**으로 화면 글자를 뽑아 확인했다.
(테스트 파일은 저장소에 넣지 않았다 — 스크래치패드에만 있다.)

```
총 30건 · 통과 30 · 실패 0   (종료 코드 0)

실적: 누적 손익 -212,040원이 찍힌다 / 부풀려진 -5,014,000원이 위쪽에 없다 /
      -2.12% / -2.98% / 9,787,960원 / "마감 기록 없음" / null이 0원으로 둔갑하지 않는다 /
      막대에서 마감 없는 날이 빠진다
옛 숫자: source 경고가 그대로 보인다 / 부풀려진 숫자는 접힌 칸에 남아 있다
진단: 전고점 10,088,806원 / 멈춤 문턱 9,079,925원 / 남은 돈 708,035원 /
      3% 넘으면 경고색 아님 / 3% 미만이면 경고색 / 음수면 위험색 / peakUnverified 배지 /
      미체결 남은 수량 3주 / 미체결 없으면 "없음" / 가동일 7일 / 필수 주의 문구 /
      1쪽에서 「이전」 잠김 / 20건이면 「다음」 살아 있음 / 3건이면 「다음」 잠김 /
      기간 바꾸면 1쪽으로
빈 자료·실패: 기록 없음 안내 / 첫 조회 실패 안내 / 이미 그린 뒤 실패해도 숫자 유지
홈: 실현손익이 실제 값으로 찍힌다 / "Sprint 3 예정" 사라짐
```

실제로 찍히는 글자(발췌):

```
<div.stat-label> 번 돈 (처음 넣은 돈과 지금의 차이)
<div.stat-value down> -212,040원
<div.stat-note> 처음 10,000,000원
...
<div.pnl-big down> 지금 낙폭 -2.98%
<div.stat-label> 멈춤까지 남은 돈      <div.stat-value flat> 708,035원
<div.stat-note> 이만큼 더 잃으면 멈춥니다
```

### ③ HTML 구조

```
열린 채 남은 태그: []   | 닫힘 불일치: 없음
tab-panel id: home, perf, diag, positions, review
data-tab    : home, perf, diag, positions, review   (두 목록이 일치 → 탭 전환 가능)
```

---

## 7. 지시 · 규칙 준수 확인

| 지시 | 결과 |
|---|---|
| `static/` 아래만, 자바 0줄 | `git diff --stat`에 `.java` 없음. 같은 시각 `backtest/*.java` 5개가 바뀌어 있는데 **다른 에이전트 작업**이고 내가 건드린 적 없다 |
| 새 차트 라이브러리 금지 | 외부 의존 0. 기존 수제 SVG 코드를 옮겨 확장 |
| 파일 300줄 | 새 파일 203 · 139 · 249 · 재작성 233. **`index.html` 315 — §2 계획** |
| `core.js` · `tab-home.js`에 얹지 않기 | 각 **1줄**만(탭 등록 / 실현손익 표시) |
| 기존 `Poll` 사용 | `setInterval` 직접 호출 0. 진단 · 실적 60초 · 미체결 10초 |
| 기존 CSS 재사용 | `card` · `orders-table` · `empty-state` · `wl-del` · `msg` · `badge-sm` · `pnl-big` · `up/down/flat` 그대로. 새 클래스는 끝에 49줄만 |
| 기존 포매터 재사용 | `KRW` · `PCT` · `SIGN` · `priceClass` · `cx` — 새 포매터 0 |
| 손익에 부호+색 | `SIGN()` + `priceClass()` (한국 관례: 이익 빨강 / 손실 파랑) |
| 앱 재시작 · gradle 실행 금지 | 둘 다 하지 않았다. 실행 중인 앱(PID 31624) 손대지 않음 |
| 없는 API 호출 · 가짜 데이터 금지 | 호출한 것은 5개뿐 — `/api/performance/account` · `/api/performance` · `/api/orders/open` · `/api/orders/filled` · `/api/trading/run-streak` (+ 홈의 기존 `/api/pnl/daily`) |
| 쉬운 우리말 | 화면에 영어 0. "MDD" → 「가장 컸던 낙폭(제일 많이 줄었던 순간)」, "미체결" → 「주문은 넣었지만 아직 사거나 팔리지 않은 것」 |

---

## 8. 확인하지 못한 것 (실행하지 않은 것은 확인했다고 적지 않는다)

1. **브라우저 렌더링 — 미확인.** 배포 전이라 볼 수 없다. 정적 파일은 **빌드 출력에서 서빙**되므로
   지금 실행 중인 앱(8080)에는 이 변경이 **보이지 않는다.** 다시 빌드 · 기동해야 나타난다 — 배포는 리더 몫.
   - 따라서 **눈으로 확인 못 한 것**: 글자 줄바꿈/겹침, 좁은 화면(모바일) 배치, 차트 선 · 막대의 실제 모양,
     마우스오버 툴팁 위치, `<details>` 접힘 화살표, 표가 가로로 넘칠 때의 스크롤.
2. **실제 서버 응답으로 돌려보지 못했다.** 위 스모크 테스트는 과제서에 적힌 값을 손으로 넣은 것이다.
   실행 중인 앱은 이번 변경 **이전** 코드라 `/api/performance/account`가 아직 없다(19_impl §5와 같은 사정).
3. **`peakUnverified=true` · `roomToThreshold<0` 같은 위험 상태의 실제 화면** — 로직은 테스트로 확인했지만
   실제 계좌가 그 상태가 된 적이 없다.
4. **`/api/performance/account`는 paper 전용**이다. 다른 프로필로 띄우면 실적 · 진단의 두 칸이
   "불러오지 못했습니다"가 된다 — 의도한 동작이지만 실물로 확인하지는 않았다.
5. 표에 **행 수 제한을 두지 않았다**. 지금은 47행이지만 `days=365`가 다 차면 수백 행이 된다.
   느려지면 그때 「최근 30일만 보기」를 붙이면 된다 — 지금 넣지 않았다(필요 없는 기능).

---

## 9. 되돌리기

전부 화면 파일이라 되돌려도 매매에 영향이 없다.

```
git checkout -- src/main/resources/static/
rm src/main/resources/static/js/chart-svg.js
rm src/main/resources/static/js/ui-parts.js
rm src/main/resources/static/js/tab-diagnostics.js
```

일부만 되돌리려면:

- 진단 탭만 빼기 → `index.html`의 진단 버튼 · 패널 + 스크립트 1줄 삭제,
  `core.js` `TAB_IDS`에서 `'diag'` 제거
- 실적 탭만 옛날로 → `git checkout -- .../js/tab-performance.js`
  (단 `chart-svg.js`와 `SVG_NS` 중복 선언이 되살아나므로 `chart-svg.js`도 같이 빼야 한다)
