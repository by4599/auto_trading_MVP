// tab-performance.js — 실적 탭
//
// 위쪽(정본): /api/performance/account — 계좌 잔고 원장으로 계산한 진짜 성적.
// 아래쪽(참고, 접힘): /api/performance — 거래 기록으로 계산한 옛 숫자. 모의투자가 판 값을
//   0원으로 주는 결함 때문에 손익이 부풀려져 있다. 지우지 않고 남겨 둔 이유는
//   "왜 두 숫자가 다른가"를 사용자가 직접 볼 수 있어야 하기 때문이다(응답의 source 문구 그대로 표시).

const ACCOUNT_DAYS = 365;

// ── 계좌 기준 성적 (정본) ─────────────────────────────────────────────────

async function loadAccountPerf() {
  const el = document.getElementById('perfAccountBody');
  if (!el) return;
  try {
    renderAccountPerf(el, await get('/api/performance/account?days=' + ACCOUNT_DAYS));
  } catch {
    showFetchError(el, '계좌 성적을 불러오지 못했습니다 (잠시 뒤 다시 시도합니다)');
  }
}

function renderAccountPerf(el, d) {
  el.textContent = '';

  if (d.currentEquity == null) {
    el.appendChild(emptyState('아직 계좌 기록이 없습니다 — 하루가 마감되면 쌓이기 시작합니다'));
    el.dataset.loaded = '1';
    return;
  }

  el.appendChild(accountStats(d));
  el.appendChild(subHeading('내 돈이 어떻게 움직였나 (총자산)'));
  el.appendChild(equityChart(d));
  el.appendChild(subHeading('하루에 번 돈 · 잃은 돈 (하루를 마감한 날만)'));
  el.appendChild(dailyBars(d.daily));
  el.appendChild(subHeading('날짜별 기록'));
  el.appendChild(dailyTable(d.daily));

  el.dataset.loaded = '1';
}

function accountStats(d) {
  return statGrid([
    {
      label: '번 돈 (처음 넣은 돈과 지금의 차이)',
      value: SIGN(d.cumulativePnl) + KRW(d.cumulativePnl),
      cls:   priceClass(d.cumulativePnl),
      note:  '처음 ' + KRW(d.initialEquity),
    },
    {
      label: '수익률 (처음 대비 몇 %)',
      value: PCT(d.cumulativeReturnPercent),
      cls:   priceClass(d.cumulativeReturnPercent),
    },
    {
      // 자동 멈춤은 "지금" 낙폭으로 판단한다 — 한도 안내는 이 칸에 붙인다
      label: '지금 낙폭 (가장 많았을 때보다 얼마나 줄었나)',
      value: PCT(d.currentDrawdownPercent),
      cls:   priceClass(d.currentDrawdownPercent),
      note:  (d.peakEquity != null ? '가장 많았을 때 ' + KRW(d.peakEquity) : '') +
             (d.mddLimitPercent != null ? ' · ' + d.mddLimitPercent + '%를 넘으면 자동으로 멈춥니다' : ''),
    },
    {
      label: '가장 컸던 낙폭 (제일 많이 줄었던 순간)',
      value: PCT(d.maxDrawdownPercent),
      cls:   priceClass(d.maxDrawdownPercent),
      note:  '이 기간 중 제일 깊었던 순간입니다 (지금은 회복했을 수도 있습니다)',
    },
    {
      label: '지금 총자산 (주식 + 현금)',
      value: KRW(d.currentEquity),
      cls:   'flat',
      note:  '마감을 찍은 날 ' + d.closedDays + '일',
    },
  ]);
}

/** 총자산 곡선 — y축은 값 범위에 맞춘다(0부터 그리면 선이 납작해진다) */
function equityChart(d) {
  const initial = d.initialEquity ?? 0;
  return buildLineChart({
    points: (d.series || []).map(p => {
      const diff = p.equity - initial;
      return {
        label: shortDate(p.date),
        value: p.equity,
        tip1:  p.date + '  총자산 ' + KRW(p.equity),
        tip2:  '처음보다 ' + SIGN(diff) + KRW(diff),
      };
    }),
    formatY:   v => Math.round(v / 10000).toLocaleString('ko-KR') + '만',
    emptyText: '총자산 기록이 아직 없습니다',
  });
}

/**
 * 일별 순손익 막대 — 마감을 찍은 날만 그린다.
 * 마감 기록이 없는 날(주말·앱이 꺼져 있던 날)은 netPnl이 null이다. 0으로 그리면
 * "그날은 본전이었다"는 거짓말이 되므로 아예 뺀다.
 */
function dailyBars(daily) {
  const closed = (daily || []).filter(r => r.closed && r.netPnl != null);
  const box = document.createElement('div');

  box.appendChild(buildBarChart({
    bars: [...closed].reverse().map(r => ({          // 원본은 최신 먼저 → 그래프는 오래된 날부터
      label: shortDate(r.date),
      value: r.netPnl,
      tip1:  r.date + '  ' + SIGN(r.netPnl) + KRW(r.netPnl),
      tip2:  '마감 총자산 ' + KRW(r.endEquity) + ' · ' + PCT(r.netPnlPercent),
    })),
    emptyText: '하루를 마감한 기록이 아직 없습니다',
  }));

  const note = document.createElement('div');
  note.className = 'stat-note';
  note.textContent = '막대가 안 보이면 그날 번 돈도 잃은 돈도 0원이라는 뜻입니다. ' +
    '마감 기록이 없는 날(주말 등)은 그래프에서 뺐습니다.';
  box.appendChild(note);
  return box;
}

function dailyTable(daily) {
  const rows = (daily || []).map(r => r.closed
    ? [
        r.date, '마감함',
        KRW(r.startEquity), KRW(r.endEquity),
        { text: SIGN(r.netPnl) + KRW(r.netPnl), cls: priceClass(r.netPnl) },
        { text: PCT(r.netPnlPercent),           cls: priceClass(r.netPnlPercent) },
      ]
    : [
        r.date, { text: '마감 기록 없음', cls: 'flat' },
        KRW(r.startEquity), { text: '—', cls: 'flat' },
        { text: '마감 기록 없음', cls: 'flat' }, { text: '—', cls: 'flat' },
      ]);

  if (rows.length === 0) return emptyState('날짜별 기록이 없습니다');
  return buildTable(['날짜', '마감', '시작 총자산', '끝 총자산', '그날 번 돈', '그날 %'], rows);
}

// ── (참고) 거래 기록 기준 옛 숫자 — 접힌 칸 ───────────────────────────────

let perfPeriod = 'daily';

async function loadLegacyPerf() {
  const el = document.getElementById('perfLegacyBody');
  if (!el) return;
  try {
    renderLegacyPerf(el, await get('/api/performance?period=' + perfPeriod + '&days=90'));
  } catch {
    showFetchError(el, '옛 실적을 불러오지 못했습니다');
  }
}

function setPerfPeriod(p) {
  perfPeriod = p;
  loadLegacyPerf();
}

function renderLegacyPerf(el, data) {
  el.textContent = '';
  el.dataset.loaded = '1';

  el.appendChild(noticeBox(
    '⚠ 아래 숫자는 믿지 마세요. 위쪽 계좌 숫자가 정답입니다.\n' + (data.source || '')));
  el.appendChild(chipRow(
    [['daily', '일별'], ['weekly', '주별'], ['monthly', '월별']], perfPeriod, setPerfPeriod));

  if (!data.buckets || data.buckets.length === 0) {
    el.appendChild(emptyState('조회 기간(90일) 안에 거래 기록이 없습니다.'));
    el.appendChild(backfillBox());
    return;
  }

  const s = data.summary;
  el.appendChild(statGrid([
    { label: '누적 손익(거래 기록)', value: SIGN(s.totalPnl) + KRW(s.totalPnl), cls: priceClass(s.totalPnl) },
    { label: '승률',      value: s.winRate != null ? s.winRate + '%' : '—', cls: 'flat' },
    { label: '평균 이익', value: s.avgWin  ? '+' + KRW(s.avgWin)  : '—',    cls: 'up' },
    { label: '평균 손실', value: s.avgLoss ? KRW(s.avgLoss)       : '—',    cls: 'down' },
  ]));

  el.appendChild(buildLineChart({
    points: data.buckets.map(b => ({
      label: b.label,
      value: b.cumPnl,
      tip1:  b.label + '  누적 ' + SIGN(b.cumPnl) + KRW(b.cumPnl),
      tip2:  '기간 손익 ' + SIGN(b.pnl) + KRW(b.pnl) + ' · ' + b.trades + '건',
    })),
    includeZero: true,
  }));

  const detail = document.createElement('div');
  detail.className = 'pnl-sub';
  detail.textContent = `거래 ${s.tradeCount}건 (승 ${s.winCount} / 패 ${s.lossCount})`;
  el.appendChild(detail);

  el.appendChild(buildTable(['기간', '손익', '누적', '거래', '승'],
    [...data.buckets].reverse().map(b => [
      b.label,
      { text: SIGN(b.pnl) + KRW(b.pnl),       cls: priceClass(b.pnl) },
      { text: SIGN(b.cumPnl) + KRW(b.cumPnl), cls: priceClass(b.cumPnl) },
      b.trades + '건',
      b.wins + '건',
    ])));
}

function backfillBox() {
  const box = document.createElement('div');
  box.style.textAlign = 'center';
  const btn = document.createElement('button');
  btn.className = 'wl-btn';
  btn.textContent = '과거 체결 내역에서 옛 실적 다시 만들기 (백필)';
  btn.onclick = runBackfill;
  box.appendChild(btn);
  return box;
}

async function runBackfill() {
  try {
    const data = await post('/api/performance/backfill', {});
    const warn = (data.warnings || []).length;
    showToast(`백필 완료: ${data.created}건 생성` + (warn ? ` (경고 ${warn}건 — 콘솔 확인)` : ''),
      warn ? 'err' : 'ok');
    if (warn) console.warn('백필 경고:', data.warnings);
    await loadLegacyPerf();
  } catch (e) {
    showToast('백필 실패: ' + e.message, 'err');
  }
}

Poll.register('perf', loadAccountPerf, 60_000);
Poll.register('perf', loadLegacyPerf,  0);      // 참고용이라 탭에 들어올 때 1회만
