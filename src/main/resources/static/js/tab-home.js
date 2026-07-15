// tab-home.js — 홈 탭: 유니버스·포지션·손익·리스크·체결·추천·공시·관심종목 카드

// ── 매매 유니버스 ────────────────────────────────────────────────────────

async function loadUniverse() {
  try {
    const list = await get('/api/universe');
    const el = document.getElementById('universeBody');
    el.textContent = '';

    if (!list || list.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'empty-state';
      empty.textContent = '유니버스 비어 있음 — 종목을 편입하면 자동 매매가 시작됩니다';
      el.appendChild(empty);
      return;
    }

    const table = document.createElement('table');
    table.className = 'orders-table';
    const tbody = document.createElement('tbody');

    // 현재가는 순차 조회 — KIS 모의계좌 레이트리밋(초당 2건) 보호
    for (const u of list) {
      let quote = null;
      try {
        const q = await get('/api/market/quote/' + u.stockCode);
        if (q.configured && !q.error) quote = q;
      } catch { /* 시세 없이 행만 표시 */ }

      const tr = document.createElement('tr');

      const tdName = document.createElement('td');
      tdName.textContent = (quote?.stockName || u.stockName || u.stockCode) + ' · ' + u.stockCode;
      tr.appendChild(tdName);

      const tdPrice = document.createElement('td');
      if (quote) {
        tdPrice.className = priceClass(quote.changeAmount);
        tdPrice.textContent = KRW(quote.currentPrice) + ' (' + PCT(quote.changeRate) + ')';
      } else {
        tdPrice.textContent = '—';
      }
      tr.appendChild(tdPrice);

      const tdDel = document.createElement('td');
      tdDel.style.textAlign = 'right';
      const btn = document.createElement('button');
      btn.className = 'wl-del';
      btn.textContent = '제외';
      btn.onclick = () => universeRemove(u.stockCode);
      tdDel.appendChild(btn);
      tr.appendChild(tdDel);

      tbody.appendChild(tr);
    }
    table.appendChild(tbody);
    el.appendChild(table);
  } catch { /* 유지 */ }
}

async function universeAdd() {
  const codeEl = document.getElementById('uvCode');
  const stockCode = codeEl.value.trim();
  if (!/^\d{6}$/.test(stockCode)) { showToast('종목코드는 6자리 숫자입니다', 'err'); return; }

  try {
    const res = await fetch('/api/universe', {
      method:  'POST',
      headers: { 'Content-Type': 'application/json' },
      body:    JSON.stringify({ stockCode }),
    });
    const data = await res.json();
    if (!res.ok) { showToast(data.error || '편입 실패', 'err'); return; }

    showToast('매매 유니버스 편입: ' + stockCode, 'ok');
    codeEl.value = '';
    await loadUniverse();
  } catch (e) {
    showToast('편입 실패: ' + e.message, 'err');
  }
}

async function universeRemove(stockCode) {
  try {
    const res = await fetch('/api/universe/' + encodeURIComponent(stockCode), { method: 'DELETE' });
    const data = await res.json();
    if (!res.ok) { showToast(data.error || '제외 실패', 'err'); return; }
    showToast('매매 유니버스 제외: ' + stockCode, 'ok');
    await loadUniverse();
  } catch (e) {
    showToast('제외 실패: ' + e.message, 'err');
  }
}

// ── 보유 포지션 (요약 카드) ──────────────────────────────────────────────

async function loadPosition() {
  try {
    const list = await get('/api/position');
    const el = document.getElementById('positionBody');
    el.textContent = '';

    if (!list || list.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'empty-state';
      empty.textContent = '보유 포지션 없음';
      el.appendChild(empty);
      return;
    }

    list.forEach(p => {
      const rows = [
        ['종목코드',   p.stockCode],
        ['보유 수량',  `${p.quantity}주`],
        ['평균 단가',  KRW(p.averagePrice)],
        ['현재가',     p.currentPrice != null ? KRW(p.currentPrice) : '—'],
        ['미실현 손익', p.unrealizedPnl != null
          ? SIGN(p.unrealizedPnl) + KRW(p.unrealizedPnl) + ' (' + PCT(p.unrealizedPnlRate) + ')'
          : '—'],
      ];
      rows.forEach(([k, v]) => {
        const row = document.createElement('div');
        row.className = 'pos-row';
        const keyEl = document.createElement('span');
        keyEl.className = 'pos-key';
        keyEl.textContent = k;
        const valEl = document.createElement('span');
        valEl.className = cx(
          'pos-val',
          k === '미실현 손익' && p.unrealizedPnl != null
            ? priceClass(p.unrealizedPnl) : ''
        );
        valEl.textContent = v;
        row.append(keyEl, valEl);
        el.appendChild(row);
      });
    });
  } catch { /* 유지 */ }
}

// ── 당일 손익 ────────────────────────────────────────────────────────────

async function loadPnl() {
  try {
    const d = await get('/api/pnl/daily');
    const pnlEl = document.getElementById('pnlValue');
    const subEl = document.getElementById('pnlSub');

    if (d.unrealizedPnl != null) {
      pnlEl.className = cx('pnl-big', priceClass(d.unrealizedPnl));
      pnlEl.textContent = SIGN(d.unrealizedPnl) + KRW(d.unrealizedPnl);
    } else {
      pnlEl.className = 'pnl-big flat';
      pnlEl.textContent = '—';
    }

    subEl.textContent = '';
    const lines = [
      `오늘 체결: ${d.tradeCount}건`,
      `실현손익: Sprint 3 예정`,
    ];
    lines.forEach(t => {
      const span = document.createElement('div');
      span.textContent = t;
      subEl.appendChild(span);
    });
  } catch { /* 유지 */ }
}

// ── 리스크 상태 ──────────────────────────────────────────────────────────

async function loadRisk() {
  try {
    const d = await get('/api/risk/status');

    const modeMap = {
      RUNNING:           ['거래 중',              'up'],
      SAFE_MODE:         ['SAFE MODE(신규매수 금지)', 'flat'],
      FORCE_LIQUIDATING: ['강제 청산 중',          'down'],
      EMERGENCY_STOPPED: ['긴급 정지 — 재가동 필요', 'down'],
    };
    const [modeLabel, modeCls] = modeMap[d.tradingMode] ?? [d.tradingMode, 'flat'];

    const modeEl = document.getElementById('riskMode');
    modeEl.className = modeCls;
    modeEl.textContent = modeLabel;

    document.getElementById('riskDailyPnl').textContent =
      PCT(d.dailyPnlPercent * 100);
    document.getElementById('riskConsecutive').textContent =
      d.consecutiveLossCount + '회';
  } catch { /* 유지 */ }
}

// ── 최근 체결 내역 ───────────────────────────────────────────────────────

async function loadOrders() {
  try {
    const list = await get('/api/orders/filled');
    const el = document.getElementById('ordersBody');
    el.textContent = '';

    if (!list || list.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'empty-state';
      empty.textContent = '체결 내역 없음';
      el.appendChild(empty);
      return;
    }

    const table = document.createElement('table');
    table.className = 'orders-table';

    const thead = document.createElement('thead');
    const hrow = document.createElement('tr');
    ['시각', '종목', '구분', '수량', '체결가', '상태'].forEach(h => {
      const th = document.createElement('th');
      th.textContent = h;
      hrow.appendChild(th);
    });
    thead.appendChild(hrow);
    table.appendChild(thead);

    const tbody = document.createElement('tbody');
    list.forEach(o => {
      const tr = document.createElement('tr');
      const isBuy = o.side === 'BUY';

      const statusLabel = {
        FILLED:        '체결',
        PARTIAL_FILLED:'부분체결',
        CANCELLED:     '취소',
        FAILED:        '실패',
        CANCEL_FAILED: '취소실패',
      }[o.status] ?? o.status;

      [
        o.requestedAt,
        o.stockCode,
        isBuy ? '매수' : '매도',
        o.filledQty + '주',
        o.filledPrice != null ? KRW(o.filledPrice) : '—',
        statusLabel,
      ].forEach((v, i) => {
        const td = document.createElement('td');
        if (i === 2) td.className = isBuy ? 'side-buy' : 'side-sell';
        td.textContent = v;
        tr.appendChild(td);
      });
      tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    el.appendChild(table);
  } catch { /* 유지 */ }
}

// ── 뉴스 기반 투자 추천 ──────────────────────────────────────────────────

const GRADE_LABEL = {
  BUY_CANDIDATE: ['매수 후보', 'up'],
  CAUTION:       ['주의',      'down'],
  NEUTRAL:       ['관망',      'flat'],
};
const SENTI_MARK = { POSITIVE: '🔴', NEGATIVE: '🔵', NEUTRAL: '⚪' };

async function loadRecommendations() {
  try {
    const list = await get('/api/research/recommendations');
    const el = document.getElementById('recoBody');
    el.textContent = '';

    if (!list || list.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'empty-state';
      empty.textContent = '관심 종목을 추가하면 뉴스 기반 추천이 표시됩니다';
      el.appendChild(empty);
      return;
    }

    const table = document.createElement('table');
    table.className = 'orders-table';

    const thead = document.createElement('thead');
    const hrow = document.createElement('tr');
    ['종목', '판정', '점수', '호재', '악재', '뉴스', '최근 헤드라인'].forEach(h => {
      const th = document.createElement('th');
      th.textContent = h;
      hrow.appendChild(th);
    });
    thead.appendChild(hrow);
    table.appendChild(thead);

    const tbody = document.createElement('tbody');
    list.forEach(r => {
      const tr = document.createElement('tr');
      const [gradeLabel, gradeCls] = GRADE_LABEL[r.grade] ?? [r.grade, 'flat'];

      const tdStock = document.createElement('td');
      tdStock.textContent = (r.stockName || r.stockCode) + ' · ' + r.stockCode;
      tr.appendChild(tdStock);

      const tdGrade = document.createElement('td');
      const gradeSpan = document.createElement('span');
      gradeSpan.className = cx('reco-grade', gradeCls);
      gradeSpan.textContent = gradeLabel;
      tdGrade.appendChild(gradeSpan);
      tr.appendChild(tdGrade);

      [SIGN(r.score) + r.score, r.positiveCount + '건', r.negativeCount + '건', r.newsCount + '건']
        .forEach(v => {
          const td = document.createElement('td');
          td.textContent = v;
          tr.appendChild(td);
        });

      const tdNews = document.createElement('td');
      (r.headlines || []).forEach(h => {
        const a = document.createElement('a');
        a.className = 'reco-headline';
        a.href = h.url;
        a.target = '_blank';
        a.rel = 'noopener noreferrer';
        a.textContent = (SENTI_MARK[h.sentiment] ?? '⚪') + ' ' + h.title;
        tdNews.appendChild(a);
      });
      if ((r.headlines || []).length === 0) tdNews.textContent = '—';
      tr.appendChild(tdNews);

      tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    el.appendChild(table);
  } catch { /* 유지 */ }
}

// ── DART 공시 ────────────────────────────────────────────────────────────

async function loadDisclosures() {
  try {
    const d = await get('/api/research/disclosures');
    const el = document.getElementById('disclosureBody');
    el.textContent = '';

    const items = d.items || [];
    if (items.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'empty-state';
      empty.textContent = d.configured
        ? '수집된 공시 없음 (30분 주기 수집 대기 중)'
        : 'DART 키를 설정하면 공시가 수집됩니다 (⚙ 설정 → DART 공시 API)';
      el.appendChild(empty);
      return;
    }

    const table = document.createElement('table');
    table.className = 'orders-table';
    const tbody = document.createElement('tbody');
    items.slice(0, 15).forEach(it => {
      const tr = document.createElement('tr');

      const tdDate = document.createElement('td');
      tdDate.textContent = it.disclosedAt;
      tr.appendChild(tdDate);

      const tdCorp = document.createElement('td');
      tdCorp.textContent = (it.corpName || it.stockCode) + ' · ' + it.stockCode;
      tr.appendChild(tdCorp);

      const tdReport = document.createElement('td');
      const a = document.createElement('a');
      a.className = 'reco-headline';
      a.href = it.url;
      a.target = '_blank';
      a.rel = 'noopener noreferrer';
      a.textContent = (SENTI_MARK[it.sentiment] ?? '⚪') + ' ' + it.reportName;
      tdReport.appendChild(a);
      tr.appendChild(tdReport);

      tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    el.appendChild(table);
  } catch { /* 유지 */ }
}

// ── 관심 종목 관리 ───────────────────────────────────────────────────────

async function loadWatchlist() {
  try {
    const list = await get('/api/research/watchlist');
    const el = document.getElementById('watchlistBody');
    el.textContent = '';

    if (!list || list.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'empty-state';
      empty.textContent = '관심 종목 없음';
      el.appendChild(empty);
      return;
    }

    const table = document.createElement('table');
    table.className = 'orders-table';
    const tbody = document.createElement('tbody');
    list.forEach(w => {
      const tr = document.createElement('tr');

      const tdName = document.createElement('td');
      tdName.textContent = (w.stockName || w.stockCode) + ' · ' + w.stockCode;
      tr.appendChild(tdName);

      const tdPrice = document.createElement('td');
      if (w.currentPrice != null) {
        tdPrice.className = priceClass(w.changeAmount ?? 0);
        tdPrice.textContent = KRW(w.currentPrice) + ' (' + PCT(w.changeRate) + ')';
      } else {
        tdPrice.textContent = '—';
      }
      tr.appendChild(tdPrice);

      const tdDel = document.createElement('td');
      tdDel.style.textAlign = 'right';
      const btn = document.createElement('button');
      btn.className = 'wl-del';
      btn.textContent = '삭제';
      btn.onclick = () => watchlistRemove(w.stockCode);
      tdDel.appendChild(btn);
      tr.appendChild(tdDel);

      tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    el.appendChild(table);
  } catch { /* 유지 */ }
}

async function watchlistAdd() {
  const codeEl = document.getElementById('wlCode');
  const nameEl = document.getElementById('wlName');
  const stockCode = codeEl.value.trim();
  if (!stockCode) { showToast('종목코드를 입력하세요', 'err'); return; }

  try {
    const res = await fetch('/api/research/watchlist', {
      method:  'POST',
      headers: { 'Content-Type': 'application/json' },
      body:    JSON.stringify({ stockCode, stockName: nameEl.value.trim() || null }),
    });
    const data = await res.json();
    if (!res.ok) { showToast(data.error || '추가 실패', 'err'); return; }

    showToast('관심 종목 추가: ' + (data.stockName || stockCode), 'ok');
    codeEl.value = '';
    nameEl.value = '';
    await Promise.allSettled([loadWatchlist(), loadRecommendations()]);
  } catch (e) {
    showToast('추가 실패: ' + e.message, 'err');
  }
}

async function watchlistRemove(stockCode) {
  try {
    const res = await fetch('/api/research/watchlist/' + encodeURIComponent(stockCode),
      { method: 'DELETE' });
    if (!res.ok) { showToast('삭제 실패 (HTTP ' + res.status + ')', 'err'); return; }
    showToast('관심 종목 삭제: ' + stockCode, 'ok');
    await Promise.allSettled([loadWatchlist(), loadRecommendations()]);
  } catch (e) {
    showToast('삭제 실패: ' + e.message, 'err');
  }
}

// ── 폴링 등록 ────────────────────────────────────────────────────────────
// 유니버스 시세: 15초 (종목 수 × KIS 레이트리밋 보호), 코어 카드: 10초, 리서치: 60초

Poll.register('home', loadUniverse, 15_000);
Poll.register('home', () => Promise.allSettled([
  loadPosition(), loadPnl(), loadRisk(), loadOrders(),
]), 10_000);
Poll.register('home', () => Promise.allSettled([
  loadRecommendations(), loadWatchlist(), loadDisclosures(),
]), 60_000);
