// diag-history.js — 진단 탭의 「기록」 두 칸 (2026-09-22 추가)
//
//   ① 상태 바뀐 기록   GET /api/trading/mode-history?days=30
//   ② 사려다 멈춘 이유 GET /api/risk/blocks?days=7
//
// tab-diagnostics.js(249줄)에 얹으면 300줄 상한을 넘기므로 파일을 나눴다.
// 이 프로젝트의 화면 모듈은 저마다 Poll.register로 자기 폴링을 등록한다 — 같은 방식이다.
// 그릴 자리(#modeHistBody · #riskBlockBody)는 index.html의 진단 탭에 있다.

const MODE_HISTORY_DAYS = 30;
const RISK_BLOCK_DAYS   = 7;

/** 개별 내역은 최근 이만큼만 그린다 (서버는 최대 1,000건까지 준다) */
const BLOCK_LIST_MAX = 50;

/** 이 두 기록은 2026-09-22에 새로 들어갔다 — 프로그램을 다시 켠 뒤부터 쌓인다 */
const HISTORY_SINCE = '2026-09-22';

const MODE_LABEL = {
  RUNNING:           '거래 중',
  SAFE_MODE:         '지키기만 함 (새로 사지 않음)',
  FORCE_LIQUIDATING: '전부 파는 중',
  EMERGENCY_STOPPED: '비상 정지',
};

/** 눈에 띄게 알려야 하는 위험한 전환 */
const ALARM_MODES = ['FORCE_LIQUIDATING', 'EMERGENCY_STOPPED'];

/** 모드를 바꾼 곳(프로그램 부품 이름)을 사람 말로 */
const SOURCE_LABEL = {
  LiquidationService:        '자동 전부 팔기 담당',
  KisApiClient:              '증권사 연결이 끊기거나 돌아와서',
  TradingController:         '사람이 버튼을 눌러서',
  ShadowPortfolioReconciler: '잔고를 다시 맞춘 뒤',
};

/** 매수를 막은 안전장치 이름 → 사람 말 (RiskRuleNameResolver가 쓰는 14개 + 모름) */
const RULE_LABEL = {
  DailyLossRule:            '오늘 너무 많이 잃어서',
  MaxPositionCountRule:     '이미 5종목을 갖고 있어서',
  GlobalEquityStopRule:     '전체 손실이 한도에 가까워서',
  PendingOrderRule:         '이미 그 종목을 사는 중이라서',
  PositionLimitRule:        '한 종목에 넣을 수 있는 돈을 넘어서',
  MarketCloseRule:          '장이 곧 끝나서',
  PostTimeCutBuyRule:       '장이 곧 끝나서 (오후 3시 15분 넘음)',
  ConsecutiveLossRule:      '연달아 져서 잠깐 쉬는 중',
  BucketBudgetRule:         '그 지갑 칸의 돈을 다 써서',
  DisclosureCooldownRule:   '공시가 나온 직후라 잠깐 피하려고',
  EntryTimeWindowRule:      '살 수 있는 시간대가 아니라서',
  IndexTrendRule:           '시장 전체가 내리막이라서',
  IndexRegimeRule:          '시장 분위기가 나빠서',
  OrderFailureCooldownRule: '조금 전 주문이 실패해서 쉬는 중',
  StaleAccountBuyGuardRule: '잔고 정보를 못 받아서 (오래된 값)',
};

/** 룰 이름을 못 알아보면(UNKNOWN 등) 원문 사유를 그대로 보여준다 */
function ruleText(ruleName, reason) {
  return RULE_LABEL[ruleName] || (reason || ruleName || '알 수 없음');
}

function historyEmptyText(what) {
  return `아직 기록이 없습니다 — ${what}은 ${HISTORY_SINCE}부터 쌓입니다 ` +
    '(오늘 새로 들어간 기능이라 프로그램을 다시 켠 뒤에 생긴 일부터 남습니다).';
}

/** '더 있습니다' 안내 — 서버가 상한에 걸려 잘라서 줬을 때 */
function truncatedNote(limit) {
  const d = document.createElement('div');
  d.className = 'stat-note';
  d.textContent = `더 있습니다 — 너무 많아서 최근 ${limit}건만 보여줍니다.`;
  return d;
}

// ── ⑤ 상태 바뀐 기록 ──────────────────────────────────────────────────────

async function loadModeHistory() {
  const el = document.getElementById('modeHistBody');
  if (!el) return;
  try {
    renderModeHistory(el, await get('/api/trading/mode-history?days=' + MODE_HISTORY_DAYS));
  } catch (e) {
    showFetchError(el, notYetText(e, '상태 바뀐 기록'));
  }
}

function renderModeHistory(el, d) {
  el.textContent = '';
  el.dataset.loaded = '1';

  const rows = d.transitions || [];
  if (rows.length === 0) {
    el.appendChild(emptyState(historyEmptyText('이 기록')));
    return;
  }

  const alarms = rows.filter(r => ALARM_MODES.includes(r.newMode)).length;
  const sub = document.createElement('div');
  sub.className = 'pnl-sub';
  sub.textContent = `최근 ${d.days}일 동안 ${rows.length}번 바뀌었습니다` +
    (d.from ? ` (${d.from}부터)` : '');
  el.appendChild(sub);

  if (alarms > 0) {
    el.appendChild(noticeBox(
      `⚠ 이 중 ${alarms}번은 위험한 전환입니다 — 「전부 파는 중」이나 「비상 정지」로 바뀐 때입니다.`));
  }

  el.appendChild(modeHistoryTable(rows));

  if (d.truncated) el.appendChild(truncatedNote(d.limit));

  const note = document.createElement('div');
  note.className = 'stat-note';
  note.textContent = '「왜」 칸은 대부분 비어 있습니다 — 프로그램이 바뀐 이유를 아직 글로 남기지 않습니다. ' +
    '대신 「누가 바꿨나」로 짐작할 수 있습니다.';
  el.appendChild(note);
}

function modeHistoryTable(rows) {
  return buildTable(
    ['언제', '어떻게 바뀌었나', '원래 이름', '누가 바꿨나', '왜'],
    rows.map(r => {
      const alarm = ALARM_MODES.includes(r.newMode);
      const before = MODE_LABEL[r.previousMode] || r.previousMode;
      const after  = MODE_LABEL[r.newMode]      || r.newMode;
      return [
        r.at,
        { text: (alarm ? '⚠ ' : '') + before + ' → ' + after, cls: alarm ? 'cell-alarm' : null },
        { text: r.previousMode + ' → ' + r.newMode, cls: 'reason-cell' },
        r.source ? (SOURCE_LABEL[r.source] || r.source) : null,
        r.reason,
      ];
    }));
}

// ── ⑥ 사려다 멈춘 이유 ────────────────────────────────────────────────────

async function loadRiskBlocks() {
  const el = document.getElementById('riskBlockBody');
  if (!el) return;
  try {
    renderRiskBlocks(el, await get('/api/risk/blocks?days=' + RISK_BLOCK_DAYS));
  } catch (e) {
    showFetchError(el, notYetText(e, '사려다 멈춘 이유'));
  }
}

function renderRiskBlocks(el, d) {
  el.textContent = '';
  el.dataset.loaded = '1';

  const byRule = d.byRule || [];
  const blocks = d.blocks || [];
  if (byRule.length === 0 && blocks.length === 0) {
    el.appendChild(emptyState(historyEmptyText('이 기록')));
    return;
  }

  const big = document.createElement('div');
  big.className = 'pnl-big flat';
  big.textContent = (d.blockedTotal != null ? d.blockedTotal : 0) + '번 막았습니다';
  el.appendChild(big);

  const sub = document.createElement('div');
  sub.className = 'pnl-sub';
  sub.textContent = `최근 ${d.days}일 기준` + (d.from ? ` (${d.from}부터)` : '') +
    ' · 「사자」 신호가 나왔지만 안전장치가 막은 횟수입니다';
  el.appendChild(sub);

  el.appendChild(subHeading('무엇이 막았나 (많이 막은 차례)'));
  el.appendChild(ruleSummaryTable(byRule));

  el.appendChild(subHeading('최근에 막은 내역'));
  el.appendChild(blockListTable(blocks));

  if (d.truncated) el.appendChild(truncatedNote(d.limit));
}

/** 룰별 집계 — 화면에는 blockedCount(실제 막은 횟수)를 쓴다. recordCount(저장된 줄 수)가 아니다 */
function ruleSummaryTable(byRule) {
  if (byRule.length === 0) return emptyState('막은 기록이 없습니다');

  return buildTable(
    ['왜 막았나', '막은 횟수', '해당 종목 수'],
    byRule.map(r => [
      RULE_LABEL[r.ruleName] || (r.ruleName === 'UNKNOWN' ? '그 밖의 이유' : r.ruleName),
      { text: r.blockedCount + '번', cls: 'flat' },
      r.stockCount + '종목',
    ]));
}

function blockListTable(blocks) {
  if (blocks.length === 0) return emptyState('막은 내역이 없습니다');

  const box = document.createElement('div');
  const shown = blocks.slice(0, BLOCK_LIST_MAX);

  box.appendChild(buildTable(
    ['언제', '종목', '왜 막았나', '이때 막은 횟수', '프로그램이 남긴 말'],
    shown.map(b => [
      b.at,
      b.stockCode,
      ruleText(b.ruleName, b.reason),
      { text: b.blockedCount + '번', cls: 'flat' },
      { text: b.reason, cls: 'reason-cell' },
    ])));

  if (blocks.length > shown.length) {
    const note = document.createElement('div');
    note.className = 'stat-note';
    note.textContent = `전체 ${blocks.length}건 중 최근 ${shown.length}건만 보여줍니다.`;
    box.appendChild(note);
  }

  const help = document.createElement('div');
  help.className = 'stat-note';
  help.textContent = '같은 종목을 같은 이유로 계속 막으면 10분에 한 줄만 남기고, ' +
    '그 사이 몇 번 더 막았는지는 「이때 막은 횟수」에 모아 적습니다.';
  box.appendChild(help);
  return box;
}

Poll.register('diag', loadModeHistory, 60_000);
Poll.register('diag', loadRiskBlocks,  60_000);
