// stats-tables.js — 「성적」 탭의 표 두 개 (종목별 · 지갑 칸별)
//
//   GET /api/performance/by-stock?days=30
//   GET /api/performance/by-bucket?days=30
//
// 숫자는 전부 추정치다 (tab-stats.js 머리말 참고). 여기서 보는 것은
// "어느 종목·어느 칸에서 벌고 잃었나"라는 분포다.
// 두 응답 모두 손익이 큰 쪽부터 온다 — 화면은 받은 차례를 그대로 쓴다.

/** 서버가 displayName을 못 줄 때만 쓰는 예비 이름표 */
const BUCKET_LABEL = {
  VB:    '방식1 · 돌파',
  EVENT: '방식2',
  MIX:   '방식3',
  TREND: 'A동 · 추세추종',
};

/** 칸마다 「무슨 방법인가」를 한 줄로 */
const BUCKET_HOW = {
  VB:    '하루 안에 갑자기 오르면 따라 사는 방법',
  EVENT: '평균값 선들이 나란히 오를 때 사는 방법',
  MIX:   '잠깐 내렸다가 다시 오를 때 재빨리 사고파는 방법',
  TREND: '20일 만에 가장 비싸지면 사서 며칠 들고 가는 방법',
};

/** 어느 표에나 들어가는 공통 숫자 칸 (거래 수 · 승률 · 번 돈 · 평균 %) */
function statsCells(r) {
  return [
    r.totalTrades + '번',
    { text: RATE(r.winRatePercent), cls: 'flat' },
    { text: SIGN(r.totalPnl) + KRW(r.totalPnl), cls: priceClass(r.totalPnl) },
    { text: PCT(r.avgReturnPercent), cls: priceClass(r.avgReturnPercent) },
  ];
}

/** 표 아래 공통 꼬리말 — 추정이라는 사실을 표마다 다시 알린다 */
function estimatedNote(d) {
  const note = document.createElement('div');
  note.className = 'stat-note';
  note.textContent = '추정한 값입니다 (수수료·세금 빼기 전).' +
    (d.unmeasurable ? ` 얼마에 팔렸는지 모르는 ${d.unmeasurable}건은 빠져 있습니다.` : '');
  return note;
}

// ── ① 종목별 성적 ─────────────────────────────────────────────────────────

async function loadStatsByStock() {
  const el = document.getElementById('statsStockBody');
  if (!el) return;
  try {
    renderStatsByStock(el, await get('/api/performance/by-stock?days=' + STATS_DAYS));
  } catch (e) {
    showFetchError(el, notYetText(e, '종목별 성적'));
  }
}

function renderStatsByStock(el, d) {
  el.textContent = '';
  el.dataset.loaded = '1';

  const rows = d.stocks || [];
  if (rows.length === 0) {
    el.appendChild(emptyState(
      `최근 ${d.days || STATS_DAYS}일 안에 사고팔기를 끝낸 종목이 없습니다.`));
    return;
  }

  const sub = document.createElement('div');
  sub.className = 'pnl-sub';
  sub.textContent = `${rows.length}종목 · 많이 번 종목부터 보여줍니다`;
  el.appendChild(sub);

  el.appendChild(buildTable(
    ['종목', '거래 수', '승률', '번 돈 (추정)', '한 거래 평균', '이김 / 짐'],
    rows.map(r => {
      const cells = statsCells(r);
      return [
        r.stockCode,
        cells[0], cells[1], cells[2], cells[3],
        `${r.wins} / ${r.losses}`,
      ];
    })));

  el.appendChild(estimatedNote(d));
}

// ── ② 지갑 칸별 성적 ──────────────────────────────────────────────────────

async function loadStatsByBucket() {
  const el = document.getElementById('statsBucketBody');
  if (!el) return;
  try {
    renderStatsByBucket(el, await get('/api/performance/by-bucket?days=' + STATS_DAYS));
  } catch (e) {
    showFetchError(el, notYetText(e, '지갑 칸별 성적'));
  }
}

function renderStatsByBucket(el, d) {
  el.textContent = '';
  el.dataset.loaded = '1';

  const rows = d.buckets || [];
  if (rows.length === 0) {
    el.appendChild(emptyState(
      '지갑 칸으로 나뉜 거래가 없습니다 — 칸 기능이 꺼져 있거나, ' +
      `최근 ${d.days || STATS_DAYS}일 안에 끝낸 거래가 없습니다.`));
    return;
  }

  const sub = document.createElement('div');
  sub.className = 'pnl-sub';
  sub.textContent = '방식마다 돈을 따로 나눠 두고 성적을 따로 셉니다';
  el.appendChild(sub);

  el.appendChild(buildTable(
    ['지갑 칸', '무슨 방법인가', '거래 수', '승률', '번 돈 (추정)', '한 거래 평균'],
    rows.map(r => {
      const cells = statsCells(r);
      return [
        r.displayName || BUCKET_LABEL[r.bucket] || r.bucket,
        { text: BUCKET_HOW[r.bucket] || '—', cls: 'reason-cell' },
        cells[0], cells[1], cells[2], cells[3],
      ];
    })));

  el.appendChild(estimatedNote(d));
}

Poll.register('stats', loadStatsByStock,  60_000);
Poll.register('stats', loadStatsByBucket, 60_000);
