// tab-stats.js — 「성적」 탭 (2026-09-22 추가) · 맨 위 경고 + 전체 요약
//
//   GET /api/performance/trades?days=30
//
// ⚠ 이 탭의 숫자는 전부 추정치다. 모의계좌가 「얼마에 팔렸는지」를 주지 않아서
//   판 시각의 시세로 되짚은 값이다(CLAUDE.md 결함 5). 정확한 금액의 정본은
//   「실적」 탭(/api/performance/account)이고, 여기서 보는 것은 "어디서 벌고
//   어디서 잃었나"라는 분포다. 그래서 경고 상자를 맨 위에 고정하고 접지 않는다.
//
// 종목별·지갑칸별 표는 stats-tables.js에 있다 (한 파일에 다 넣으면 300줄을 넘는다).

const STATS_DAYS = 30;

/** 승률처럼 부호를 붙이면 안 되는 퍼센트 (PCT는 양수에 +를 붙인다) */
const RATE = n => (n == null ? '—' : n.toFixed(1) + '%');

/** 손익비·번돈나눗셈처럼 「몇 배」로 읽는 값 */
const RATIO = n => (n == null ? '—' : n.toFixed(2) + '배');

const STATS_WARNING =
  '⚠ 여기 숫자는 추정치입니다.\n' +
  '증권사 모의계좌가 "얼마에 팔렸는지"를 알려주지 않아서, ' +
  '판 시각의 시세로 되짚어 계산한 값입니다.\n' +
  '정확한 금액은 「실적」 탭의 계좌 숫자를 보세요.';

// ── ① 맨 위 고정 경고 (서버를 부르지 않는다 — 항상 보인다) ────────────────

function renderStatsWarning() {
  const el = document.getElementById('statsWarnBody');
  if (!el) return;
  el.textContent = '';
  el.appendChild(noticeBox(STATS_WARNING, 'warn lead'));

  const row = document.createElement('div');
  row.className = 'btn-row';
  const btn = document.createElement('button');
  btn.className = 'wl-del';
  btn.textContent = '정확한 금액 보러 가기 (실적 탭)';
  btn.onclick = () => switchTab('perf');
  row.appendChild(btn);
  el.appendChild(row);
}

// ── ② 전체 요약 ───────────────────────────────────────────────────────────

async function loadTradeStats() {
  const el = document.getElementById('statsSummaryBody');
  if (!el) return;
  try {
    renderTradeStats(el, await get('/api/performance/trades?days=' + STATS_DAYS));
  } catch (e) {
    showFetchError(el, notYetText(e, '거래 성적'));
  }
}

function renderTradeStats(el, d) {
  el.textContent = '';
  el.dataset.loaded = '1';

  if (!d.totalTrades) {
    el.appendChild(emptyState(
      `최근 ${d.days || STATS_DAYS}일 안에 사고팔기를 끝낸 거래가 없습니다 ` +
      '(아직 안 판 주식은 여기에 들어오지 않습니다).'));
    el.appendChild(unmeasurableBox(d));
    return;
  }

  el.appendChild(statGrid(statsBoxes(d)));
  el.appendChild(sellSourceNote(d.sellSource));
  el.appendChild(unmeasurableBox(d));

  if (d.warning) {
    const src = document.createElement('div');
    src.className = 'stat-note';
    src.textContent = '프로그램이 남긴 말: ' + d.warning;
    el.appendChild(src);
  }
}

/** 요약 칸 — 어려운 말에는 전부 쉬운 설명을 붙인다 */
function statsBoxes(d) {
  return [
    {
      label: '거래 수 (사고팔기를 한 번 끝낸 횟수)',
      value: d.totalTrades + '번',
      cls:   'flat',
      note:  `이김 ${d.wins}번 · 짐 ${d.losses}번 · 본전 ${d.breakEven}번`,
    },
    {
      label: '승률 (100번 중 몇 번 이겼나)',
      value: RATE(d.winRatePercent),
      cls:   'flat',
      note:  '이긴 거래 ÷ 전체 거래',
    },
    {
      label: '손익비',
      value: RATIO(d.payoffRatio),
      cls:   'flat',
      note:  '이길 때 버는 돈이 질 때 잃는 돈의 몇 배인가. 1배보다 크면 좋습니다',
    },
    {
      label: '평균 이익 (이긴 거래 한 번에 번 돈)',
      value: d.avgProfit ? '+' + KRW(d.avgProfit) : '—',
      cls:   'up',
    },
    {
      label: '평균 손실 (진 거래 한 번에 잃은 돈)',
      value: d.avgLoss ? KRW(d.avgLoss) : '—',
      cls:   'down',
      note:  '잃은 금액의 크기입니다',
    },
    {
      label: '번 돈 다 더하기 (추정)',
      value: SIGN(d.totalPnl) + KRW(d.totalPnl),
      cls:   priceClass(d.totalPnl),
      note:  '수수료·세금은 빼지 않은 값입니다 — 정확한 금액은 실적 탭',
    },
    {
      label: '번 돈 ÷ 잃은 돈',
      value: RATIO(d.profitFactor),
      cls:   'flat',
      note:  '1배보다 크면 번 돈이 더 많다는 뜻입니다',
    },
    {
      label: '한 거래 평균 수익률',
      value: PCT(d.avgReturnPercent),
      cls:   priceClass(d.avgReturnPercent),
      note:  '한 번 사고팔 때 평균 몇 % 움직였나',
    },
  ];
}

/** 판 가격을 어디서 되짚었는지 — 추정의 출처를 숨기지 않는다 */
function sellSourceNote(src) {
  const note = document.createElement('div');
  note.className = 'stat-note';
  if (!src) {
    note.textContent = '판 가격을 어디서 되짚었는지는 알 수 없습니다.';
    return note;
  }
  note.textContent = '판 가격을 되짚은 곳: ' +
    `1분마다 적힌 시세 ${src.minute ?? 0}건 · 하루 시세 ${src.daily ?? 0}건 · ` +
    `못 구한 것 ${src.none ?? 0}건`;
  return note;
}

// ── ③ 계산에서 빠진 거래 (반드시 눈에 보이게) ─────────────────────────────

function unmeasurableBox(d) {
  const missing = d.unmeasurable || 0;
  if (missing === 0) {
    const ok = document.createElement('div');
    ok.className = 'stat-note';
    ok.textContent = '빠진 거래 없음 — 이 기간에 판 것은 모두 값을 되짚을 수 있었습니다.';
    return ok;
  }

  const lines = [
    `⚠ ${missing}건은 얼마에 팔렸는지 알 수 없어 계산에서 뺐습니다` +
    (d.unmeasurableQuantity ? ` (주식 ${d.unmeasurableQuantity}주).` : '.'),
  ];
  (d.unmeasurableReasons || []).forEach(r => {
    lines.push(` · ${r.reason} — ${r.count}건 (${r.quantity}주)`);
  });
  lines.push('빠진 거래는 0원으로 꾸미지 않고 아예 세지 않았습니다.');
  return noticeBox(lines.join('\n'));
}

Poll.register('stats', renderStatsWarning, 0);   // 서버를 안 부르므로 탭에 들어올 때 1회
Poll.register('stats', loadTradeStats,     60_000);
