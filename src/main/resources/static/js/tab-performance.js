// tab-performance.js — 실적 탭: 기간별 누적 성과 (요약 스탯 + SVG 누적 곡선 + 버킷 테이블)

let perfPeriod = 'daily';

async function loadPerformance() {
  const el = document.getElementById('perfBody');
  if (!el) return;
  try {
    const data = await get('/api/performance?period=' + perfPeriod + '&days=90');
    renderPerformance(el, data);
  } catch { /* 유지 */ }
}

function setPerfPeriod(p) {
  perfPeriod = p;
  loadPerformance();
}

function renderPerformance(el, data) {
  el.textContent = '';
  const s = data.summary;

  // ── 기간 토글 ──
  const toggle = document.createElement('div');
  toggle.style.cssText = 'display:flex;gap:6px;margin-bottom:14px';
  [['daily', '일별'], ['weekly', '주별'], ['monthly', '월별']].forEach(([key, label]) => {
    const btn = document.createElement('button');
    btn.className = 'wl-del';
    btn.textContent = label;
    if (key === perfPeriod) {
      btn.style.color = 'var(--blue)';
      btn.style.borderColor = 'var(--blue)';
      btn.style.fontWeight = '700';
    }
    btn.onclick = () => setPerfPeriod(key);
    toggle.appendChild(btn);
  });
  el.appendChild(toggle);

  // ── 데이터 없음: 백필 안내 ──
  if (!data.buckets || data.buckets.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'empty-state';
    empty.textContent = '조회 기간(90일) 내 실현손익 기록이 없습니다.';
    el.appendChild(empty);

    const hint = document.createElement('div');
    hint.style.cssText = 'text-align:center;padding-bottom:8px';
    const btn = document.createElement('button');
    btn.className = 'wl-btn';
    btn.textContent = '과거 체결 내역에서 실적 생성 (백필)';
    btn.onclick = runBackfill;
    hint.appendChild(btn);
    const note = document.createElement('div');
    note.className = 'empty-state';
    note.style.paddingTop = '8px';
    note.textContent = '매도 체결이 발생하면 자동으로 기록됩니다. 과거 기록 복원은 위 버튼 1회면 충분합니다.';
    hint.appendChild(note);
    el.appendChild(hint);
    return;
  }

  // ── 요약 스탯 4칸 ──
  const stats = document.createElement('div');
  stats.style.cssText = 'display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-bottom:16px';
  [
    ['누적 손익', SIGN(s.totalPnl) + KRW(s.totalPnl), priceClass(s.totalPnl)],
    ['승률', s.winRate != null ? s.winRate + '%' : '—', 'flat'],
    ['평균 이익', s.avgWin ? '+' + KRW(s.avgWin) : '—', 'up'],
    ['평균 손실', s.avgLoss ? KRW(s.avgLoss) : '—', 'down'],
  ].forEach(([label, value, cls]) => {
    const box = document.createElement('div');
    box.style.cssText = 'background:var(--bg);border:1px solid var(--border);border-radius:10px;padding:12px 14px';
    const l = document.createElement('div');
    l.className = 'card-label';
    l.style.marginBottom = '4px';
    l.textContent = label;
    const v = document.createElement('div');
    v.className = cls;
    v.style.cssText = 'font-size:1.05rem;font-weight:700';
    v.textContent = value;
    box.append(l, v);
    stats.appendChild(box);
  });
  el.appendChild(stats);

  // ── 누적 손익 곡선 (인라인 SVG) ──
  el.appendChild(buildCumPnlChart(data.buckets));

  // ── 상세 요약 라인 ──
  const detail = document.createElement('div');
  detail.className = 'pnl-sub';
  detail.style.marginBottom = '12px';
  const parts = [`거래 ${s.tradeCount}건 (승 ${s.winCount} / 패 ${s.lossCount})`];
  if (s.bestDay)  parts.push(`최고일 ${s.bestDay.date} ${SIGN(s.bestDay.pnl)}${KRW(s.bestDay.pnl)}`);
  if (s.worstDay) parts.push(`최악일 ${s.worstDay.date} ${SIGN(s.worstDay.pnl)}${KRW(s.worstDay.pnl)}`);
  detail.textContent = parts.join(' · ');
  el.appendChild(detail);

  // ── 버킷 테이블 ──
  const table = document.createElement('table');
  table.className = 'orders-table';
  const thead = document.createElement('thead');
  const hrow = document.createElement('tr');
  ['기간', '손익', '누적', '거래', '승'].forEach(h => {
    const th = document.createElement('th');
    th.textContent = h;
    hrow.appendChild(th);
  });
  thead.appendChild(hrow);
  table.appendChild(thead);

  const tbody = document.createElement('tbody');
  [...data.buckets].reverse().forEach(b => {   // 최근이 위로
    const tr = document.createElement('tr');
    const cells = [
      [b.label, 'flat'],
      [SIGN(b.pnl) + KRW(b.pnl), priceClass(b.pnl)],
      [SIGN(b.cumPnl) + KRW(b.cumPnl), priceClass(b.cumPnl)],
      [b.trades + '건', ''],
      [b.wins + '건', ''],
    ];
    cells.forEach(([v, cls]) => {
      const td = document.createElement('td');
      if (cls) td.className = cls;
      td.textContent = v;
      tr.appendChild(td);
    });
    tbody.appendChild(tr);
  });
  table.appendChild(tbody);
  el.appendChild(table);
}

// ── SVG 누적 손익 곡선 (외부 라이브러리 없음) ─────────────────────────────

const SVG_NS = 'http://www.w3.org/2000/svg';

function svgEl(tag, attrs) {
  const e = document.createElementNS(SVG_NS, tag);
  for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
  return e;
}

function buildCumPnlChart(buckets) {
  const W = 720, H = 240, PAD_L = 64, PAD_R = 14, PAD_T = 14, PAD_B = 26;
  const plotW = W - PAD_L - PAD_R, plotH = H - PAD_T - PAD_B;

  const values = [0, ...buckets.map(b => b.cumPnl)];   // 시작점 0
  const vMin = Math.min(...values), vMax = Math.max(...values);
  const range = (vMax - vMin) || 1;

  const x = i => PAD_L + (values.length === 1 ? 0 : (i / (values.length - 1)) * plotW);
  const y = v => PAD_T + (1 - (v - vMin) / range) * plotH;

  const wrap = document.createElement('div');
  wrap.style.cssText = 'overflow-x:auto;margin-bottom:14px';
  const svg = svgEl('svg', { viewBox: `0 0 ${W} ${H}`, width: '100%' });
  svg.style.minWidth = '480px';

  // y축 그리드 + 라벨 (4단계)
  for (let i = 0; i <= 3; i++) {
    const v = vMin + (range * i) / 3;
    const yy = y(v);
    svg.appendChild(svgEl('line', {
      x1: PAD_L, y1: yy, x2: W - PAD_R, y2: yy,
      stroke: '#21262d', 'stroke-width': 1,
    }));
    const label = svgEl('text', {
      x: PAD_L - 8, y: yy + 4, 'text-anchor': 'end',
      fill: '#8b949e', 'font-size': 11,
    });
    label.textContent = Math.round(v).toLocaleString('ko-KR');
    svg.appendChild(label);
  }

  // 0 기준선 (범위 안에 있을 때만, 점선)
  if (vMin < 0 && vMax > 0) {
    svg.appendChild(svgEl('line', {
      x1: PAD_L, y1: y(0), x2: W - PAD_R, y2: y(0),
      stroke: '#6e7681', 'stroke-width': 1, 'stroke-dasharray': '4 3',
    }));
  }

  // x축 라벨: 처음/중간/끝
  const labelIdx = [...new Set([0, Math.floor((buckets.length - 1) / 2), buckets.length - 1])];
  labelIdx.forEach(i => {
    if (i < 0) return;
    const t = svgEl('text', {
      x: x(i + 1), y: H - 8, 'text-anchor': 'middle',
      fill: '#8b949e', 'font-size': 11,
    });
    t.textContent = buckets[i].label;
    svg.appendChild(t);
  });

  // 누적 곡선 — 최종 손익 부호로 색 결정 (한국 관례: 이익 빨강 / 손실 파랑)
  const finalPnl = values[values.length - 1];
  const color = finalPnl >= 0 ? '#f78166' : '#58a6ff';
  const points = values.map((v, i) => `${x(i)},${y(v)}`).join(' ');
  svg.appendChild(svgEl('polyline', {
    points, fill: 'none', stroke: color, 'stroke-width': 2,
    'stroke-linejoin': 'round', 'stroke-linecap': 'round',
  }));

  // 마지막 점 강조
  svg.appendChild(svgEl('circle', {
    cx: x(values.length - 1), cy: y(finalPnl), r: 3.5, fill: color,
  }));

  // 마우스오버 툴팁 (가장 가까운 버킷으로 스냅)
  const guide = svgEl('line', {
    x1: 0, y1: PAD_T, x2: 0, y2: H - PAD_B,
    stroke: '#8b949e', 'stroke-width': 1, 'stroke-dasharray': '3 3', visibility: 'hidden',
  });
  svg.appendChild(guide);
  const tipBg = svgEl('rect', {
    x: 0, y: 0, rx: 6, width: 170, height: 40,
    fill: '#161b22', stroke: '#30363d', visibility: 'hidden',
  });
  const tip1 = svgEl('text', { x: 0, y: 0, fill: '#e6edf3', 'font-size': 11, visibility: 'hidden' });
  const tip2 = svgEl('text', { x: 0, y: 0, fill: '#8b949e', 'font-size': 11, visibility: 'hidden' });
  svg.append(tipBg, tip1, tip2);

  svg.addEventListener('mousemove', ev => {
    const rect = svg.getBoundingClientRect();
    const mx = (ev.clientX - rect.left) * (W / rect.width);
    let best = 1, bestDist = Infinity;
    for (let i = 1; i < values.length; i++) {
      const d = Math.abs(x(i) - mx);
      if (d < bestDist) { bestDist = d; best = i; }
    }
    const b = buckets[best - 1];
    const gx = x(best);
    guide.setAttribute('x1', gx); guide.setAttribute('x2', gx);
    guide.setAttribute('visibility', 'visible');

    const tx = Math.min(gx + 10, W - 180);
    tipBg.setAttribute('x', tx); tipBg.setAttribute('y', PAD_T + 4);
    tip1.setAttribute('x', tx + 10); tip1.setAttribute('y', PAD_T + 20);
    tip2.setAttribute('x', tx + 10); tip2.setAttribute('y', PAD_T + 35);
    tip1.textContent = b.label + '  누적 ' + SIGN(b.cumPnl) + b.cumPnl.toLocaleString('ko-KR') + '원';
    tip2.textContent = '기간 손익 ' + SIGN(b.pnl) + b.pnl.toLocaleString('ko-KR') + '원 · ' + b.trades + '건';
    [tipBg, tip1, tip2].forEach(e => e.setAttribute('visibility', 'visible'));
  });
  svg.addEventListener('mouseleave', () => {
    [guide, tipBg, tip1, tip2].forEach(e => e.setAttribute('visibility', 'hidden'));
  });

  wrap.appendChild(svg);
  return wrap;
}

async function runBackfill() {
  try {
    const data = await post('/api/performance/backfill', {});
    const warn = (data.warnings || []).length;
    showToast(`백필 완료: ${data.created}건 생성` + (warn ? ` (경고 ${warn}건 — 콘솔 확인)` : ''),
      warn ? 'err' : 'ok');
    if (warn) console.warn('백필 경고:', data.warnings);
    await loadPerformance();
  } catch (e) {
    showToast('백필 실패: ' + e.message, 'err');
  }
}

Poll.register('perf', loadPerformance, 60_000);
