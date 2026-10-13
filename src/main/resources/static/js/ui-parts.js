// ui-parts.js — 여러 탭이 함께 쓰는 화면 조각 (요약 칸 · 표 · 안내문 · 선택 버튼)
//
// 실적 탭과 진단 탭이 같은 모양의 요약 칸과 표를 여러 번 만든다. 같은 코드를
// 파일마다 베껴 쓰지 않으려고 여기로 모았다. 숫자 포매터(KRW·PCT·SIGN)와
// cx()는 core.js 것을 그대로 쓴다 — 여기서 새로 만들지 않는다.

/** 내용이 없을 때 보여주는 회색 안내문 */
function emptyState(text) {
  const d = document.createElement('div');
  d.className = 'empty-state';
  d.textContent = text;
  return d;
}

/**
 * 첫 조회가 실패했을 때만 오류 문구로 바꾼다.
 * 이미 한 번 그려 둔 화면은 그대로 둔다 — 잠깐 끊겼다고 숫자가 사라지면 더 불안하다.
 */
function showFetchError(el, text) {
  if (el.dataset.loaded === '1') return;
  el.textContent = '';
  el.appendChild(emptyState(text));
}

/** 노란/빨간 주의 상자 (core.js showMsg와 같은 모양). 줄바꿈(\n)을 그대로 살린다 */
function noticeBox(text, tone = 'warn') {
  const d = document.createElement('div');
  d.className = cx('msg', tone);
  d.style.display = 'block';
  d.style.whiteSpace = 'pre-line';
  d.textContent = text;
  return d;
}

/** 카드 안 작은 소제목 */
function subHeading(text) {
  const d = document.createElement('div');
  d.className = 'sub-hdr';
  d.textContent = text;
  return d;
}

/**
 * 요약 칸 묶음.
 * items: [{ label, value, cls, note, tone, badge }]
 *   cls  — 숫자 색 (priceClass 결과: up/down/flat)
 *   tone — 칸 테두리 강조 ('warn' | 'danger')
 */
function statGrid(items) {
  const grid = document.createElement('div');
  grid.className = 'stat-grid';
  items.forEach(it => {
    const box = document.createElement('div');
    box.className = cx('stat-box', it.tone);

    const label = document.createElement('div');
    label.className = 'stat-label';
    label.textContent = it.label;
    box.appendChild(label);

    const value = document.createElement('div');
    value.className = cx('stat-value', it.cls);
    value.textContent = it.value;
    box.appendChild(value);

    if (it.note) {
      const note = document.createElement('div');
      note.className = 'stat-note';
      note.textContent = it.note;
      box.appendChild(note);
    }
    if (it.badge) {
      const badge = document.createElement('span');
      badge.className = 'badge-sm warn';
      badge.textContent = it.badge;
      box.appendChild(badge);
    }
    grid.appendChild(box);
  });
  return grid;
}

/**
 * 표 만들기.
 * headers: ['날짜', ...]
 * rows:    [[셀, 셀, ...], ...]   셀 = 문자열 또는 { text, cls }
 * 값이 null이면 '—'로 찍는다.
 */
function buildTable(headers, rows) {
  const table = document.createElement('table');
  table.className = 'orders-table';

  const thead = document.createElement('thead');
  const hrow = document.createElement('tr');
  headers.forEach(h => {
    const th = document.createElement('th');
    th.textContent = h;
    hrow.appendChild(th);
  });
  thead.appendChild(hrow);

  const tbody = document.createElement('tbody');
  rows.forEach(cells => {
    const tr = document.createElement('tr');
    cells.forEach(c => {
      const cell = (c !== null && typeof c === 'object') ? c : { text: c };
      const td = document.createElement('td');
      if (cell.cls) td.className = cell.cls;
      td.textContent = cell.text == null ? '—' : cell.text;
      tr.appendChild(td);
    });
    tbody.appendChild(tr);
  });

  table.append(thead, tbody);
  return table;
}

/**
 * 고르기 버튼 한 줄 (기간 선택 등).
 * options: [[key, label], ...] · currentKey와 같은 버튼만 파랗게 강조
 */
function chipRow(options, currentKey, onPick) {
  const row = document.createElement('div');
  row.className = 'btn-row';
  options.forEach(([key, label]) => {
    const btn = document.createElement('button');
    btn.className = cx('wl-del', key === currentKey && 'active');
    btn.textContent = label;
    btn.onclick = () => onPick(key);
    row.appendChild(btn);
  });
  return row;
}

/** '2026-09-21' → '09/21' (그래프 x축용) */
function shortDate(iso) {
  return iso ? iso.slice(5).replace('-', '/') : '';
}

/**
 * 조회가 실패했을 때 보여줄 글. 404(서버에 아직 그 기능이 없음)와 진짜 오류를 나눈다.
 * 새 기능은 프로그램을 다시 켜야 생기므로, 그동안 화면이 「고장」처럼 보이면 안 된다.
 */
function notYetText(err, what) {
  if (String(err && err.message).includes('404')) {
    return '아직 준비 중입니다 — ' + what + ' 기능은 프로그램을 다시 켠 뒤부터 보입니다';
  }
  return what + ' 정보를 불러오지 못했습니다 (잠시 뒤 다시 시도합니다)';
}
