// chart-svg.js — 손으로 그리는 SVG 차트 (외부 차트 라이브러리 없음)
//
// tab-performance.js 안에 있던 누적손익 곡선 코드를 재사용 가능하게 뽑은 것.
// 두 가지를 그린다:
//   buildLineChart({points})  — 선 그래프. y축을 값 범위에 맞춘다(0을 바닥으로 깔지 않는다).
//                               총자산처럼 1,000만원 근처에서 조금씩 움직이는 값도 납작해지지 않는다.
//   buildBarChart({bars})     — 막대 그래프. 0선 위아래로 양수/음수 색을 나눈다.
//
// 자료 한 칸: { label, value, tip1, tip2 }  (tip*는 마우스를 올렸을 때 보이는 글)
// 넘겨받은 배열은 읽기만 한다 — 원본을 바꾸지 않는다.

const SVG_NS  = 'http://www.w3.org/2000/svg';
const C_GRID  = '#21262d';
const C_AXIS  = '#8b949e';
const C_ZERO  = '#6e7681';
const C_UP    = '#f78166';   // 한국 관례: 이익 = 빨강
const C_DOWN  = '#58a6ff';   //            손실 = 파랑
const C_TIPBG = '#161b22';
const C_TIPBD = '#30363d';
const C_TIPTX = '#e6edf3';

const CHART_W = 720;
const CHART_H = 240;
const PAD = { l: 64, r: 14, t: 14, b: 26 };
const AREA = { w: CHART_W - PAD.l - PAD.r, h: CHART_H - PAD.t - PAD.b };

function svgEl(tag, attrs) {
  const e = document.createElementNS(SVG_NS, tag);
  for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
  return e;
}

function chartShell() {
  const wrap = document.createElement('div');
  wrap.className = 'chart-wrap';
  const svg = svgEl('svg', { viewBox: `0 0 ${CHART_W} ${CHART_H}`, width: '100%' });
  wrap.appendChild(svg);
  return { wrap, svg };
}

function chartEmpty(text) {
  const d = document.createElement('div');
  d.className = 'empty-state';
  d.textContent = text;
  return d;
}

/** 위아래 여백 8%를 준 y축 범위. 값이 전부 같으면 선이 테두리에 붙지 않게 살짝 벌린다. */
function chartScale(values, includeZero) {
  const pool = includeZero ? [...values, 0] : values;
  const lo = Math.min(...pool);
  const hi = Math.max(...pool);
  if (lo === hi) return { min: lo - (Math.abs(lo) * 0.01 + 1), max: hi + (Math.abs(hi) * 0.01 + 1) };
  const pad = (hi - lo) * 0.08;
  return { min: lo - pad, max: hi + pad };
}

const defaultFormatY = v => Math.round(v).toLocaleString('ko-KR');

function drawGrid(svg, scale, yAt, formatY) {
  const fmt = formatY || defaultFormatY;
  for (let i = 0; i <= 3; i++) {
    const v = scale.min + ((scale.max - scale.min) * i) / 3;
    const yy = yAt(v);
    svg.appendChild(svgEl('line', {
      x1: PAD.l, y1: yy, x2: CHART_W - PAD.r, y2: yy, stroke: C_GRID, 'stroke-width': 1,
    }));
    const label = svgEl('text', {
      x: PAD.l - 8, y: yy + 4, 'text-anchor': 'end', fill: C_AXIS, 'font-size': 11,
    });
    label.textContent = fmt(v);
    svg.appendChild(label);
  }
}

/** 0이 범위 안에 있을 때만 점선 기준선 */
function drawZeroLine(svg, scale, yAt) {
  if (scale.min >= 0 || scale.max <= 0) return;
  svg.appendChild(svgEl('line', {
    x1: PAD.l, y1: yAt(0), x2: CHART_W - PAD.r, y2: yAt(0),
    stroke: C_ZERO, 'stroke-width': 1, 'stroke-dasharray': '4 3',
  }));
}

/** x축 글자는 처음·중간·끝 세 개만 (촘촘하면 겹쳐서 못 읽는다) */
function drawXLabels(svg, items, xAt) {
  const idx = [...new Set([0, Math.floor((items.length - 1) / 2), items.length - 1])];
  idx.forEach(i => {
    if (i < 0 || !items[i]) return;
    const t = svgEl('text', {
      x: xAt(i), y: CHART_H - 8, 'text-anchor': 'middle', fill: C_AXIS, 'font-size': 11,
    });
    t.textContent = items[i].label;
    svg.appendChild(t);
  });
}

/** 마우스를 올리면 가장 가까운 칸으로 붙어서 설명을 띄운다 */
function attachTooltip(svg, items, xAt) {
  const guide = svgEl('line', {
    x1: 0, y1: PAD.t, x2: 0, y2: CHART_H - PAD.b,
    stroke: C_AXIS, 'stroke-width': 1, 'stroke-dasharray': '3 3', visibility: 'hidden',
  });
  const tipBg = svgEl('rect', {
    x: 0, y: 0, rx: 6, width: 215, height: 40,
    fill: C_TIPBG, stroke: C_TIPBD, visibility: 'hidden',
  });
  const tip1 = svgEl('text', { x: 0, y: 0, fill: C_TIPTX, 'font-size': 11, visibility: 'hidden' });
  const tip2 = svgEl('text', { x: 0, y: 0, fill: C_AXIS,  'font-size': 11, visibility: 'hidden' });
  svg.append(guide, tipBg, tip1, tip2);

  svg.addEventListener('mousemove', ev => {
    const rect = svg.getBoundingClientRect();
    if (rect.width === 0) return;
    const mx = (ev.clientX - rect.left) * (CHART_W / rect.width);
    let best = 0;
    let bestDist = Infinity;
    for (let i = 0; i < items.length; i++) {
      const d = Math.abs(xAt(i) - mx);
      if (d < bestDist) { bestDist = d; best = i; }
    }
    const gx = xAt(best);
    guide.setAttribute('x1', gx);
    guide.setAttribute('x2', gx);
    guide.setAttribute('visibility', 'visible');

    const tx = Math.min(Math.max(gx + 10, PAD.l), CHART_W - 225);
    tipBg.setAttribute('x', tx);
    tipBg.setAttribute('y', PAD.t + 4);
    tip1.setAttribute('x', tx + 10); tip1.setAttribute('y', PAD.t + 20);
    tip2.setAttribute('x', tx + 10); tip2.setAttribute('y', PAD.t + 35);
    tip1.textContent = items[best].tip1 ?? items[best].label;
    tip2.textContent = items[best].tip2 ?? '';
    [tipBg, tip1, tip2].forEach(e => e.setAttribute('visibility', 'visible'));
  });
  svg.addEventListener('mouseleave', () => {
    [guide, tipBg, tip1, tip2].forEach(e => e.setAttribute('visibility', 'hidden'));
  });
}

// ── 선 그래프 ─────────────────────────────────────────────────────────────
// opts: { points, formatY, color, includeZero, emptyText }

function buildLineChart(opts) {
  const points = opts.points || [];
  if (points.length === 0) return chartEmpty(opts.emptyText || '그릴 자료가 없습니다');

  const { wrap, svg } = chartShell();
  const values = points.map(p => p.value);
  const scale  = chartScale(values, !!opts.includeZero);
  const xAt = i => PAD.l + (points.length === 1 ? AREA.w / 2 : (i / (points.length - 1)) * AREA.w);
  const yAt = v => PAD.t + (1 - (v - scale.min) / (scale.max - scale.min)) * AREA.h;

  drawGrid(svg, scale, yAt, opts.formatY);
  drawZeroLine(svg, scale, yAt);
  drawXLabels(svg, points, xAt);

  const last  = values[values.length - 1];
  const color = opts.color || (last >= values[0] ? C_UP : C_DOWN);
  svg.appendChild(svgEl('polyline', {
    points: points.map((p, i) => `${xAt(i)},${yAt(p.value)}`).join(' '),
    fill: 'none', stroke: color, 'stroke-width': 2,
    'stroke-linejoin': 'round', 'stroke-linecap': 'round',
  }));
  svg.appendChild(svgEl('circle', {
    cx: xAt(points.length - 1), cy: yAt(last), r: 3.5, fill: color,
  }));

  attachTooltip(svg, points, xAt);
  return wrap;
}

// ── 막대 그래프 ───────────────────────────────────────────────────────────
// opts: { bars, formatY, emptyText }  — 0은 항상 눈금 안에 들어간다

function buildBarChart(opts) {
  const bars = opts.bars || [];
  if (bars.length === 0) return chartEmpty(opts.emptyText || '그릴 자료가 없습니다');

  const { wrap, svg } = chartShell();
  const scale = chartScale(bars.map(b => b.value), true);
  const slot  = AREA.w / bars.length;
  const barW  = Math.max(2, Math.min(28, slot * 0.6));
  const xAt = i => PAD.l + slot * (i + 0.5);
  const yAt = v => PAD.t + (1 - (v - scale.min) / (scale.max - scale.min)) * AREA.h;

  drawGrid(svg, scale, yAt, opts.formatY);
  drawZeroLine(svg, scale, yAt);
  drawXLabels(svg, bars, xAt);

  const y0 = yAt(0);
  bars.forEach((b, i) => {
    const y = yAt(b.value);
    svg.appendChild(svgEl('rect', {
      x: xAt(i) - barW / 2, y: Math.min(y, y0), width: barW,
      height: Math.max(1, Math.abs(y - y0)),
      fill: b.value >= 0 ? C_UP : C_DOWN, rx: 2,
    }));
  });

  attachTooltip(svg, bars, xAt);
  return wrap;
}
