// tab-diagnostics.js — 진단 탭 (지금 이 계좌가 안전한가를 한 화면에서 본다)
//
// 들어 있는 것: ① 낙폭 감시(자동 멈춤선까지 얼마 남았나) ② 미체결 주문
//               ③ 연속 무중단 가동일 ④ 체결 내역 전체 보기(기간·쪽 넘김)
//
// 이 탭을 만든 이유: 2026-09-10부터 7거래일 동안 매매가 0건이었는데 아무도 몰랐다.
// 계좌를 멈춰 세운 숫자(전고점·낙폭)가 어느 화면에도 없었기 때문이다.

const DIAG_DAYS = 365;
const ORDER_PAGE_SIZE = 20;

const OPEN_STATUS_LABEL = {
  ACCEPTED:         '접수됨 (아직 체결 안 됨)',
  CANCEL_REQUESTED: '취소해달라고 요청함',
};

const SETTLED_STATUS_LABEL = {
  FILLED:         '체결',
  PARTIAL_FILLED: '부분체결',
  CANCELLED:      '취소',
  FAILED:         '실패',
  CANCEL_FAILED:  '취소실패',
};

// ── ① 낙폭 감시 ───────────────────────────────────────────────────────────

async function loadDrawdownWatch() {
  const el = document.getElementById('ddBody');
  if (!el) return;
  try {
    renderDrawdownWatch(el, await get('/api/performance/account?days=' + DIAG_DAYS));
  } catch {
    showFetchError(el, '계좌 숫자를 불러오지 못했습니다 (잠시 뒤 다시 시도합니다)');
  }
}

function renderDrawdownWatch(el, d) {
  el.textContent = '';

  if (d.currentEquity == null) {
    el.appendChild(emptyState('아직 계좌 기록이 없습니다'));
    el.dataset.loaded = '1';
    return;
  }

  const big = document.createElement('div');
  big.className = cx('pnl-big', priceClass(d.currentDrawdownPercent));
  big.textContent = '지금 낙폭 ' + PCT(d.currentDrawdownPercent);
  el.appendChild(big);

  el.appendChild(statGrid([
    {
      label: '가장 많았을 때 (전고점)',
      value: KRW(d.peakEquity),
      cls:   'flat',
      badge: d.peakUnverified ? '사람 확인 대기' : null,
    },
    { label: '지금 총자산', value: KRW(d.currentEquity), cls: 'flat' },
    {
      label: '지금 낙폭',
      value: PCT(d.currentDrawdownPercent),
      cls:   priceClass(d.currentDrawdownPercent),
      note:  d.mddLimitPercent != null ? '한도 ' + d.mddLimitPercent + '%' : null,
    },
    { label: '자동 멈춤 선', value: KRW(d.forcedStopThreshold), cls: 'flat' },
    {
      // 남은 돈이 많은 건 좋은 상태라 색을 칠하지 않는다 — 위험할 때만 칸 테두리로 알린다
      label: '멈춤까지 남은 돈',
      value: KRW(d.roomToThreshold),
      cls:   (d.roomToThreshold != null && d.roomToThreshold < 0) ? 'down' : 'flat',
      tone:  roomTone(d.roomToThreshold, d.currentEquity),
      note:  '이만큼 더 잃으면 멈춥니다',
    },
  ]));

  const note = document.createElement('div');
  note.className = 'stat-note';
  note.textContent = '총자산이 「자동 멈춤 선」 아래로 내려가면 프로그램이 스스로 가진 주식을 전부 팔고 멈춥니다. ' +
    '「가장 많았을 때」는 지금까지 계좌가 제일 컸던 금액이고, 멈춤 선은 그 금액에서 한도만큼 내려간 자리입니다.';
  el.appendChild(note);

  el.dataset.loaded = '1';
}

/** 남은 돈이 총자산의 3% 미만이면 노란 경고, 이미 넘었으면 빨간 위험 */
function roomTone(room, equity) {
  if (room == null || equity == null) return null;
  if (room < 0) return 'danger';
  if (room < equity * 0.03) return 'warn';
  return null;
}

// ── ② 미체결 주문 ─────────────────────────────────────────────────────────

async function loadOpenOrders() {
  const el = document.getElementById('openOrdersBody');
  if (!el) return;
  try {
    renderOpenOrders(el, await get('/api/orders/open'));
  } catch {
    showFetchError(el, '미체결 주문을 불러오지 못했습니다');
  }
}

function renderOpenOrders(el, list) {
  el.textContent = '';
  el.dataset.loaded = '1';

  if (!list || list.length === 0) {
    el.appendChild(emptyState('없음 — 지금 시장에 걸려 있는 주문이 없습니다'));
    return;
  }

  el.appendChild(buildTable(
    ['접수 시각', '종목', '사고팔기', '주문 수량', '아직 안 된 수량', '상태', '주문번호'],
    list.map(o => {
      const isBuy = o.side === 'BUY';
      return [
        o.requestedAt,
        o.stockCode,
        { text: isBuy ? '사기' : '팔기', cls: isBuy ? 'side-buy' : 'side-sell' },
        o.quantity + '주' + (o.filledQty > 0 ? ` (${o.filledQty}주 됨)` : ''),
        { text: o.pendingQty + '주', cls: 'flat' },
        OPEN_STATUS_LABEL[o.status] ?? o.status,
        o.orderNo,
      ];
    })));
}

// ── ③ 연속 무중단 가동일 ──────────────────────────────────────────────────

async function loadRunStreak() {
  const el = document.getElementById('streakBody');
  if (!el) return;
  try {
    renderRunStreak(el, await get('/api/trading/run-streak'));
  } catch {
    showFetchError(el, '가동일 기록을 불러오지 못했습니다');
  }
}

function renderRunStreak(el, d) {
  el.textContent = '';
  el.dataset.loaded = '1';

  const big = document.createElement('div');
  big.className = cx('pnl-big', d.achieved ? 'up' : 'flat');
  big.textContent = d.streakDays + '일 연속';
  el.appendChild(big);

  const sub = document.createElement('div');
  sub.className = 'pnl-sub';
  sub.textContent = `목표 ${d.goalDays}일` + (d.achieved ? ' — 달성' : '') +
    (d.lastRecordedDate ? ` · 마지막 기록 ${d.lastRecordedDate}` : ' · 기록 없음');
  el.appendChild(sub);

  el.appendChild(noticeBox(
    '이 숫자는 「앱이 켜져 있었는지」만 셉니다. 매매를 했는지는 보지 않습니다.'));
}

// ── ④ 체결 내역 전체 보기 ─────────────────────────────────────────────────

let ordDays = '30';   // '7' | '30' | '90' | 'all'
let ordPage = 0;

const ORD_PERIODS = [['7', '7일'], ['30', '30일'], ['90', '90일'], ['all', '전체']];

async function loadAllOrders() {
  const el = document.getElementById('allOrdersBody');
  if (!el) return;
  const query = `/api/orders/filled?page=${ordPage}&size=${ORDER_PAGE_SIZE}` +
    (ordDays === 'all' ? '' : `&days=${ordDays}`);
  try {
    renderAllOrders(el, await get(query));
  } catch {
    showFetchError(el, '체결 내역을 불러오지 못했습니다');
  }
}

function setOrdPeriod(key) {
  ordDays = key;
  ordPage = 0;          // 기간을 바꾸면 첫 쪽부터 다시 본다
  loadAllOrders();
}

function moveOrdPage(delta) {
  ordPage = Math.max(0, ordPage + delta);
  loadAllOrders();
}

function renderAllOrders(el, list) {
  el.textContent = '';
  el.dataset.loaded = '1';

  el.appendChild(chipRow(ORD_PERIODS, ordDays, setOrdPeriod));

  const rows = list || [];
  if (rows.length === 0) {
    el.appendChild(emptyState(ordPage === 0
      ? '이 기간에는 체결 내역이 없습니다'
      : '더 볼 내역이 없습니다 — 「이전」을 누르세요'));
  } else {
    el.appendChild(buildTable(
      ['시각', '종목', '사고팔기', '수량', '체결가', '상태'],
      rows.map(o => {
        const isBuy = o.side === 'BUY';
        return [
          o.requestedAt,
          o.stockCode,
          { text: isBuy ? '사기' : '팔기', cls: isBuy ? 'side-buy' : 'side-sell' },
          o.filledQty + '주',
          o.filledPrice != null ? KRW(o.filledPrice) : '—',
          SETTLED_STATUS_LABEL[o.status] ?? o.status,
        ];
      })));
  }

  el.appendChild(orderPager(rows.length));
}

/** 총건수를 주는 API가 없다 — 받은 건수가 한 쪽 크기보다 적으면 마지막 쪽으로 본다 */
function orderPager(count) {
  const row = document.createElement('div');
  row.className = 'btn-row';

  const prev = document.createElement('button');
  prev.className = 'wl-del';
  prev.textContent = '← 이전';
  prev.disabled = ordPage === 0;
  prev.onclick = () => moveOrdPage(-1);

  const next = document.createElement('button');
  next.className = 'wl-del';
  next.textContent = '다음 →';
  next.disabled = count < ORDER_PAGE_SIZE;
  next.onclick = () => moveOrdPage(1);

  const label = document.createElement('span');
  label.className = 'stat-note';
  label.textContent = `${ordPage + 1}쪽 · 이 쪽 ${count}건`;

  row.append(prev, label, next);
  return row;
}

Poll.register('diag', loadDrawdownWatch, 60_000);
Poll.register('diag', loadRunStreak,     60_000);
Poll.register('diag', loadOpenOrders,    10_000);
Poll.register('diag', loadAllOrders,     0);   // 쪽 넘김 상태를 지우지 않으려고 1회만
