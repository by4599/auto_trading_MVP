// tab-review.js — 검토종목 탭: 유니버스 종목별 변동성 돌파 검토 현황
// 카드 클릭 → 인라인 상세 패널 (이미 받은 데이터 렌더, 추가 API 호출 없음)

const reviewExpanded = new Set();   // 펼쳐진 종목 코드 유지 (폴링 갱신에도 유지)

async function loadReview() {
  const el = document.getElementById('reviewBody');
  if (!el) return;
  try {
    const list = await get('/api/review/candidates');
    renderReview(el, list);
  } catch { /* 유지 */ }
}

function renderReview(el, list) {
  el.textContent = '';

  if (!list || list.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'empty-state';
    empty.textContent = '매매 유니버스가 비어 있습니다 — 홈 탭에서 종목을 편입하면 여기서 검토 현황을 볼 수 있습니다';
    el.appendChild(empty);
    return;
  }

  const note = document.createElement('div');
  note.className = 'pnl-sub';
  note.style.marginBottom = '12px';
  note.textContent = '목표가 = 당일 시가 + (전일 고가 − 전일 저가) × K. 현재가가 목표가를 넘으면 전략이 매수 신호를 냅니다 (리스크 룰 통과 시).';
  el.appendChild(note);

  const grid = document.createElement('div');
  grid.style.cssText = 'display:grid;grid-template-columns:1fr;gap:10px';

  list.forEach(r => grid.appendChild(buildReviewCard(r)));
  el.appendChild(grid);
}

function buildReviewCard(r) {
  const card = document.createElement('div');
  card.style.cssText = 'background:var(--bg);border:1px solid var(--border);border-radius:10px;padding:14px 16px;cursor:pointer';
  if (r.brokeOut === true) card.style.borderColor = 'var(--red)';

  // ── 헤더 행: 종목명 · 배지 · 현재가 ──
  const head = document.createElement('div');
  head.style.cssText = 'display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap';

  const left = document.createElement('div');
  const name = document.createElement('span');
  name.style.cssText = 'font-weight:700;font-size:0.92rem';
  name.textContent = (r.stockName || r.stockCode) + ' · ' + r.stockCode;
  left.appendChild(name);

  if (r.brokeOut === true) {
    const badge = document.createElement('span');
    badge.className = 'badge stopped';
    badge.style.marginLeft = '8px';
    badge.textContent = '🚀 돌파';
    left.appendChild(badge);
  }
  const cooldown = r.filters?.disclosureCooldown;
  if (cooldown?.active) {
    const badge = document.createElement('span');
    badge.className = 'badge warning';
    badge.style.marginLeft = '8px';
    badge.textContent = '공시 쿨다운';
    left.appendChild(badge);
  }
  head.appendChild(left);

  const right = document.createElement('div');
  right.style.cssText = 'font-size:0.86rem;font-weight:600';
  if (r.currentPrice != null) {
    right.className = priceClass(r.changeAmount ?? 0);
    right.textContent = KRW(r.currentPrice) + ' (' + PCT(r.changeRate) + ')';
  } else {
    right.className = 'flat';
    right.textContent = '시세 없음';
  }
  head.appendChild(right);
  card.appendChild(head);

  // ── 게이지 바: 시가 → 목표가 구간에서 현재가 위치 ──
  if (r.targetPrice != null && r.currentPrice != null && r.todayOpen != null) {
    card.appendChild(buildGauge(r));
  } else {
    const wait = document.createElement('div');
    wait.className = 'pnl-sub';
    wait.textContent = r.targetPrice == null
      ? '목표가 미확정 — 개장 전이거나 당일 시가 조회 대기 중'
      : '현재가 조회 대기 중';
    card.appendChild(wait);
  }

  // ── 상세 패널 (클릭 토글) ──
  const detail = buildReviewDetail(r);
  detail.style.display = reviewExpanded.has(r.stockCode) ? '' : 'none';
  card.appendChild(detail);

  card.onclick = () => {
    const open = detail.style.display !== 'none';
    detail.style.display = open ? 'none' : '';
    if (open) reviewExpanded.delete(r.stockCode);
    else reviewExpanded.add(r.stockCode);
  };

  return card;
}

function buildGauge(r) {
  const wrap = document.createElement('div');
  wrap.style.cssText = 'margin-top:10px';

  const span = Math.max(r.targetPrice - r.todayOpen, 1);
  const progress = Math.max(0, Math.min((r.currentPrice - r.todayOpen) / span, 1));

  const labels = document.createElement('div');
  labels.style.cssText = 'display:flex;justify-content:space-between;font-size:0.72rem;color:var(--muted);margin-bottom:4px';
  const l1 = document.createElement('span');
  l1.textContent = '시가 ' + KRW(r.todayOpen);
  const l2 = document.createElement('span');
  l2.textContent = r.brokeOut
    ? '목표가 ' + KRW(r.targetPrice) + ' 돌파!'
    : '목표가 ' + KRW(r.targetPrice) + ' (' + (r.distancePct != null ? '+' + r.distancePct.toFixed(2) + '% 남음' : '—') + ')';
  if (r.brokeOut) l2.style.color = 'var(--red)';
  labels.append(l1, l2);
  wrap.appendChild(labels);

  const bar = document.createElement('div');
  bar.style.cssText = 'height:8px;background:#21262d;border-radius:6px;overflow:hidden';
  const fill = document.createElement('div');
  fill.style.cssText = 'height:100%;border-radius:6px;transition:width 0.4s';
  fill.style.width = (progress * 100).toFixed(1) + '%';
  fill.style.background = r.brokeOut ? 'var(--red)' : 'var(--blue)';
  bar.appendChild(fill);
  wrap.appendChild(bar);

  return wrap;
}

function buildReviewDetail(r) {
  const detail = document.createElement('div');
  detail.style.cssText = 'margin-top:12px;border-top:1px solid #21262d;padding-top:10px';

  const rows = [
    ['전일 고가', r.yesterdayHigh != null ? KRW(r.yesterdayHigh) : '—'],
    ['전일 저가', r.yesterdayLow != null ? KRW(r.yesterdayLow) : '—'],
    ['전일 종가', r.yesterdayClose != null ? KRW(r.yesterdayClose) : '—'],
    ['당일 시가', r.todayOpen != null ? KRW(r.todayOpen) : '—'],
    ['돌파 계수 K', String(r.k)],
    ['목표가 산식', r.yesterdayHigh != null && r.targetPrice != null
      ? `${KRW(r.todayOpen)} + (${KRW(r.yesterdayHigh)} − ${KRW(r.yesterdayLow)}) × ${r.k} = ${KRW(r.targetPrice)}`
      : '개장 전 미확정'],
    ['목표가까지', r.distancePct != null
      ? (r.brokeOut ? '돌파 상태 (현재가가 목표가 위)' : '+' + r.distancePct.toFixed(2) + '%')
      : '—'],
  ];

  const senti = { POSITIVE: '🔴 호재', NEGATIVE: '🔵 악재', NEUTRAL: '⚪ 중립' }[r.sentiment];
  if (senti) rows.push(['최근 뉴스 감성', senti]);

  rows.forEach(([k, v]) => {
    const row = document.createElement('div');
    row.className = 'pos-row';
    const keyEl = document.createElement('span');
    keyEl.className = 'pos-key';
    keyEl.textContent = k;
    const valEl = document.createElement('span');
    valEl.className = 'pos-val';
    valEl.textContent = v;
    row.append(keyEl, valEl);
    detail.appendChild(row);
  });

  // 필터 상태
  const fhdr = document.createElement('div');
  fhdr.className = 'section-hdr';
  fhdr.textContent = '진입 필터 상태';
  detail.appendChild(fhdr);

  const f = r.filters || {};
  const filterRows = [];

  const cd = f.disclosureCooldown || {};
  filterRows.push(['공시 쿨다운', !cd.enabled ? 'OFF'
    : cd.active ? `🚫 차단 중 — ${cd.eventType} (${cd.until}까지)` : '✅ 통과']);

  const ew = f.entryWindow || {};
  filterRows.push(['진입 시간창', !ew.enabled ? 'OFF'
    : ew.ok ? `✅ 통과 (${ew.notBefore} 이후)` : `⏳ 대기 (${ew.notBefore} 이전)`]);

  filterRows.push(['거래량 확인',   f.volumeConfirm?.enabled ? 'ON (신호 시점 판정)' : 'OFF']);
  filterRows.push(['지수 레짐',     f.indexRegime?.enabled ? 'ON' : 'OFF']);
  filterRows.push(['트레일링 스탑', f.trailingStop?.enabled ? 'ON (보유 중 청산 감시)' : 'OFF']);

  filterRows.forEach(([k, v]) => {
    const row = document.createElement('div');
    row.className = 'pos-row';
    const keyEl = document.createElement('span');
    keyEl.className = 'pos-key';
    keyEl.textContent = k;
    const valEl = document.createElement('span');
    valEl.textContent = v;
    row.append(keyEl, valEl);
    detail.appendChild(row);
  });

  return detail;
}

Poll.register('review', loadReview, 20_000);
