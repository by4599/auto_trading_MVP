// core.js — 공용 유틸 + 폴링 매니저 + 탭 라우터 + 설정 모달 + 거래 제어
// 각 탭 모듈(tab-*.js)은 Poll.register(tabId, fn, ms)로 폴링을 등록하고,
// DOMContentLoaded 시점의 init()이 해시 기준 활성 탭을 켠다.

// ── 유틸 ─────────────────────────────────────────────────────────────────

const cx = (...parts) => parts.filter(Boolean).join(' ');

const KRW = n => (n == null ? '—' : n.toLocaleString('ko-KR') + '원');
const VOL = n => {
  if (n == null) return '—';
  return n >= 1_000_000
    ? (n / 1_000_000).toFixed(1) + 'M주'
    : n.toLocaleString('ko-KR') + '주';
};
const SIGN = n => n > 0 ? '+' : '';
const PCT  = n => (n == null ? '—' : SIGN(n) + n.toFixed(2) + '%');

function priceClass(n) {
  if (n > 0) return 'up';
  if (n < 0) return 'down';
  return 'flat';
}

// ── 토스트 알림 ──────────────────────────────────────────────────────────

let toastTimer = null;
function showToast(text, type = 'ok') {
  const el = document.getElementById('toast');
  el.textContent = text;
  el.className = cx('toast', type, 'show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), 3000);
}

// ── 공통 fetch ────────────────────────────────────────────────────────────

async function get(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error('HTTP ' + res.status);
  return res.json();
}

async function post(url, body) {
  const res = await fetch(url, {
    method:  'POST',
    headers: { 'Content-Type': 'application/json' },
    body:    JSON.stringify(body),
  });
  if (!res.ok) throw new Error('HTTP ' + res.status);
  return res.json();
}

// ── 폴링 매니저 ──────────────────────────────────────────────────────────
// 활성 탭('*'은 전 탭 공통)의 폴링만 가동한다. ms=0이면 탭 진입 시 1회만 실행.
// 문서가 백그라운드로 가면 전체 정지, 복귀 시 활성 탭 재가동.

const Poll = (() => {
  const regs = [];
  let activeTab = 'home';

  function register(tab, fn, ms) {
    regs.push({ tab, fn, ms, timer: null });
  }

  function stopAll() {
    regs.forEach(r => {
      if (r.timer) clearInterval(r.timer);
      r.timer = null;
    });
  }

  function startFor(tab) {
    activeTab = tab;
    stopAll();
    regs
      .filter(r => r.tab === tab || r.tab === '*')
      .forEach(r => {
        r.fn();
        if (r.ms > 0) r.timer = setInterval(r.fn, r.ms);
      });
  }

  function refreshActive() {
    regs
      .filter(r => r.tab === activeTab || r.tab === '*')
      .forEach(r => r.fn());
  }

  document.addEventListener('visibilitychange', () => {
    if (document.hidden) stopAll();
    else startFor(activeTab);
  });

  return { register, startFor, stopAll, refreshActive, current: () => activeTab };
})();

// ── 탭 라우터 (해시 기반) ────────────────────────────────────────────────

const TAB_IDS = ['home', 'perf', 'positions', 'review'];

function switchTab(id) {
  if (!TAB_IDS.includes(id)) id = 'home';
  document.querySelectorAll('.tab-panel').forEach(p =>
    p.classList.toggle('active', p.id === 'tab-' + id));
  document.querySelectorAll('.tab-btn').forEach(b =>
    b.classList.toggle('active', b.dataset.tab === id));
  if (location.hash !== '#' + id) history.replaceState(null, '', '#' + id);
  Poll.startFor(id);
}

window.addEventListener('hashchange', () =>
  switchTab(location.hash.slice(1) || 'home'));

// ── 거래 제어 (상단 바 — 전 탭 공통) ─────────────────────────────────────

async function tradingStart() {
  setCtrlBusy(true);
  try {
    const data = await post('/api/trading/start', {});
    showToast(data.message, data.success ? 'ok' : 'err');
    if (data.success) await loadStatus();
  } catch (e) {
    showToast('시작 실패: ' + e.message, 'err');
  } finally {
    setCtrlBusy(false);
  }
}

async function tradingStop() {
  setCtrlBusy(true);
  try {
    const data = await post('/api/trading/stop', {});
    showToast(data.message, data.success ? 'ok' : 'err');
    if (data.success) await loadStatus();
  } catch (e) {
    showToast('중지 실패: ' + e.message, 'err');
  } finally {
    setCtrlBusy(false);
  }
}

// 재가동 게이트 (OPERATIONS §6) — EMERGENCY_STOPPED에서 /start로 직접 못 돌아가므로
// 원인 진단을 reason으로 남기고 /resume을 호출해 SAFE_MODE로 전환한다.
// SAFE_MODE 도달 후에는 기존 "시작" 버튼(/start)으로 RUNNING을 눌러야 한다.
async function tradingResume() {
  const reason = prompt('재가동 사유를 입력하세요 (원인 진단 기록 — PERFORMANCE-GOVERNANCE §6)');
  if (reason == null || reason.trim() === '') { showToast('재가동을 취소했습니다', 'err'); return; }

  setCtrlBusy(true);
  try {
    const data = await post('/api/trading/resume', { confirm: 'CONFIRM_RESUME', reason: reason.trim() });
    showToast(data.message, data.success ? 'ok' : 'err');
    if (data.success) await loadStatus();
  } catch (e) {
    showToast('재가동 실패: ' + e.message, 'err');
  } finally {
    setCtrlBusy(false);
  }
}

function setCtrlBusy(busy) {
  document.getElementById('btnStart').disabled  = busy;
  document.getElementById('btnStop').disabled   = busy;
  document.getElementById('btnResume').disabled = busy;
}

// ── 상태 (상단 바 배지 — 전 탭 공통 폴링) ────────────────────────────────

async function loadStatus() {
  try {
    const data = await get('/api/status');
    updateModeBadge(data.configured, data.tradingMode);
    const banner = document.getElementById('uncfgBanner');
    if (banner) banner.style.display = data.configured ? 'none' : '';
    updateCtrlButtons(data.configured, data.tradingMode);
    return data;
  } catch {
    setModeBadge('unknown', '서버 연결 중...');
    return null;
  }
}

function updateModeBadge(configured, mode) {
  if (!configured) { setModeBadge('warning', '설정 필요'); return; }
  const map = {
    RUNNING:           ['running', '거래 중'],
    SAFE_MODE:         ['warning', 'SAFE MODE (신규매수 금지)'],
    FORCE_LIQUIDATING: ['stopped', '강제 청산 중'],
    EMERGENCY_STOPPED: ['stopped', '정지됨 — 재가동 필요'],
  };
  const [cls, label] = map[mode] ?? ['unknown', mode];
  setModeBadge(cls, label);
}

function setModeBadge(cls, label) {
  const el = document.getElementById('modeBadge');
  el.className = cx('badge', cls);
  el.textContent = label;
}

// 재가동 게이트(OPERATIONS §6): EMERGENCY_STOPPED는 "재개" 버튼(/resume)만 보이고
// "시작"(/start)은 EMERGENCY_STOPPED에서 서버가 거부하므로 노출하지 않는다.
// FORCE_LIQUIDATING은 청산 상태머신이 소유한 구간이라 어떤 버튼도 보이지 않는다.
function updateCtrlButtons(configured, mode) {
  const btnStart  = document.getElementById('btnStart');
  const btnStop   = document.getElementById('btnStop');
  const btnResume = document.getElementById('btnResume');
  if (!configured) {
    btnStart.style.display  = 'none';
    btnStop.style.display   = 'none';
    btnResume.style.display = 'none';
    return;
  }
  btnResume.style.display = mode === 'EMERGENCY_STOPPED' ? '' : 'none';
  btnStart.style.display  = mode === 'SAFE_MODE' ? '' : 'none';
  btnStop.style.display   = (mode === 'RUNNING' || mode === 'SAFE_MODE') ? '' : 'none';
}

// ── 설정 모달 ─────────────────────────────────────────────────────────────

function openSettings() {
  document.getElementById('overlay').classList.add('open');
  loadSettingStatus();
  loadParams();
}
function closeSettings() { document.getElementById('overlay').classList.remove('open'); }
function overlayClick(e) { if (e.target.id === 'overlay') closeSettings(); }

function switchModalTab(tab) {
  document.getElementById('modalTabApi').style.display    = tab === 'api'    ? '' : 'none';
  document.getElementById('modalTabParams').style.display = tab === 'params' ? '' : 'none';
  document.getElementById('modalTabBtnApi').classList.toggle('active', tab === 'api');
  document.getElementById('modalTabBtnParams').classList.toggle('active', tab === 'params');
}

// ── 투자 파라미터 탭 ──────────────────────────────────────────────────────
// 서버 값은 소수(0.03), 퍼센트형 입력은 % 단위(3)로 표시 — 전송 시 되돌린다.

let paramMeta = {};      // key → {type, percent, loadedValue}

function paramDisplay(p, raw) {
  if (p.type === 'NUMBER' && p.percent) {
    return String(parseFloat((parseFloat(raw) * 100).toPrecision(10)));
  }
  return raw;
}

function paramRaw(p, display) {
  if (p.type === 'NUMBER' && p.percent) {
    return String(parseFloat((parseFloat(display) / 100).toPrecision(10)));
  }
  return display;
}

async function loadParams() {
  const container = document.getElementById('paramGroups');
  try {
    const data = await get('/api/params');
    renderParams(data.groups);
  } catch {
    container.textContent = '';
    const err = document.createElement('div');
    err.className = 'empty-state';
    err.textContent = '파라미터 조회 실패';
    container.appendChild(err);
  }
}

function renderParams(groups) {
  const container = document.getElementById('paramGroups');
  container.textContent = '';
  paramMeta = {};

  groups.forEach(g => {
    const hdr = document.createElement('div');
    hdr.className = 'section-hdr';
    hdr.textContent = g.name;
    container.appendChild(hdr);

    g.params.forEach(p => {
      paramMeta[p.key] = { type: p.type, percent: p.percent, loadedValue: p.value };

      const row = document.createElement('div');
      row.className = 'param-row';

      const labelWrap = document.createElement('div');
      labelWrap.className = 'param-label';
      labelWrap.textContent = p.label;

      const hint = document.createElement('span');
      hint.className = 'param-hint';
      hint.textContent = paramHintText(p);
      labelWrap.appendChild(hint);

      const errEl = document.createElement('span');
      errEl.className = 'param-error';
      errEl.id = 'perr-' + p.key;
      labelWrap.appendChild(errEl);

      row.appendChild(labelWrap);

      if (p.type === 'BOOL') {
        const toggle = document.createElement('label');
        toggle.className = 'param-toggle';
        const input = document.createElement('input');
        input.type = 'checkbox';
        input.id = 'param-' + p.key;
        input.checked = p.value === 'true';
        const knob = document.createElement('span');
        knob.className = 'knob';
        toggle.append(input, knob);
        row.appendChild(toggle);
      } else {
        const input = document.createElement('input');
        input.id = 'param-' + p.key;
        if (p.type === 'TIME') {
          input.type = 'time';
          input.value = p.value;
          if (p.min) input.min = p.min;
          if (p.max) input.max = p.max;
        } else {
          input.type = 'number';
          input.step = 'any';
          input.value = paramDisplay(p, p.value);
          if (p.min != null) input.min = paramDisplay(p, p.min);
          if (p.max != null) input.max = paramDisplay(p, p.max);
        }
        row.appendChild(input);

        const unit = document.createElement('span');
        unit.className = 'param-unit';
        unit.textContent = p.percent ? '%' : '';
        row.appendChild(unit);
      }

      container.appendChild(row);
    });
  });
}

function paramHintText(p) {
  const d = paramDisplay(p, p.defaultValue);
  if (p.min == null) return `기본 ${p.type === 'BOOL' ? (p.defaultValue === 'true' ? 'ON' : 'OFF') : d}`;
  const unit = p.percent ? '%' : '';
  return `기본 ${d}${unit} · 범위 ${paramDisplay(p, p.min)}${unit} ~ ${paramDisplay(p, p.max)}${unit}`;
}

function collectChangedParams() {
  const changed = {};
  for (const [key, meta] of Object.entries(paramMeta)) {
    const input = document.getElementById('param-' + key);
    if (!input) continue;
    const current = meta.type === 'BOOL'
      ? String(input.checked)
      : (input.value === '' ? '' : paramRaw(meta, input.value));
    if (current !== '' && current !== meta.loadedValue) changed[key] = current;
  }
  return changed;
}

async function saveParams() {
  const msg = document.getElementById('paramMsg');
  document.querySelectorAll('#paramGroups input.err').forEach(i => i.classList.remove('err'));
  document.querySelectorAll('#paramGroups .param-error').forEach(e => e.textContent = '');

  const changed = collectChangedParams();
  if (Object.keys(changed).length === 0) {
    showMsg(msg, 'ok', '변경된 값이 없습니다');
    return;
  }

  try {
    const res = await fetch('/api/params', {
      method:  'PUT',
      headers: { 'Content-Type': 'application/json' },
      body:    JSON.stringify(changed),
    });
    const data = await res.json();

    const errorKeys = Object.keys(data.errors || {});
    errorKeys.forEach(key => {
      const input = document.getElementById('param-' + key);
      if (input) input.classList.add('err');
      const errEl = document.getElementById('perr-' + key);
      if (errEl) errEl.textContent = data.errors[key];
    });

    if (errorKeys.length > 0) {
      showMsg(msg, 'err', `저장 실패 ${errorKeys.length}건 — 항목별 메시지를 확인하세요` +
        (data.saved.length ? ` (성공 ${data.saved.length}건)` : ''));
    } else {
      showMsg(msg, 'ok', `✅ 저장 완료: ${data.saved.length}건 즉시 반영`);
    }
    if (data.saved.length > 0) await loadParams();
  } catch (e) {
    showMsg(msg, 'err', '저장 실패: ' + e.message);
  }
}

async function resetParams() {
  if (!confirm('모든 투자 파라미터를 기본값으로 되돌립니다. 계속할까요?')) return;
  const msg = document.getElementById('paramMsg');
  try {
    const data = await post('/api/params/reset', {});
    renderParams(data.groups);
    showMsg(msg, 'ok', '✅ 기본값으로 원복했습니다');
  } catch (e) {
    showMsg(msg, 'err', '원복 실패: ' + e.message);
  }
}

async function loadSettingStatus() {
  const container = document.getElementById('settingStatusRows');
  container.textContent = '조회 중...';
  try {
    const data = await get('/api/settings');
    container.textContent = '';
    for (const [key, v] of Object.entries(data)) {
      const isSet = v.set === 'true';
      const row = document.createElement('div');
      row.className = 'status-row';

      const keyEl = document.createElement('span');
      keyEl.className = 'status-key';
      keyEl.textContent = key;

      const right = document.createElement('span');
      if (v.preview) {
        const pre = document.createElement('span');
        pre.className = 'preview-text';
        pre.textContent = v.preview;
        right.appendChild(pre);
      }
      const badge = document.createElement('span');
      badge.className = cx('badge-sm', isSet ? 'set' : 'notset');
      badge.textContent = isSet ? '설정됨' : '미설정';
      right.appendChild(badge);

      row.append(keyEl, right);
      container.appendChild(row);
    }
  } catch { container.textContent = '상태 조회 실패'; }
}

function toggle(id) {
  const el = document.getElementById(id);
  el.type = el.type === 'password' ? 'text' : 'password';
}

async function saveKis() {
  await doSave({
    KIS_APPKEY:     document.getElementById('appkey').value,
    KIS_SECRETKEY:  document.getElementById('secretkey').value,
    KIS_ACCOUNT_NO: document.getElementById('account_no').value,
  }, 'kisMsg');
}

async function saveTelegram() {
  await doSave({
    TELEGRAM_BOT_TOKEN: document.getElementById('tg_token').value,
    TELEGRAM_CHAT_ID:   document.getElementById('tg_chat').value,
  }, 'tgMsg');
}

async function saveDart() {
  await doSave({
    DART_API_KEY: document.getElementById('dart_key').value,
  }, 'dartMsg');
}

async function doSave(body, msgId) {
  const msg = document.getElementById(msgId);
  try {
    const res = await fetch('/api/settings', {
      method:  'POST',
      headers: { 'Content-Type': 'application/json' },
      body:    JSON.stringify(body),
    });
    const data = await res.json();

    if (data.errors && data.errors.length > 0) {
      showMsg(msg, 'err', '오류: ' + data.errors.join(', '));
      return;
    }
    if (!data.saved || data.saved.length === 0) {
      showMsg(msg, 'err', '저장할 값이 없습니다 (빈 칸은 무시됩니다)');
      return;
    }

    const suffix = data.configured ? ' — 거래 연결!' : '';
    showMsg(msg, 'ok', '✅ 저장: ' + data.saved.join(', ') + suffix);
    loadSettingStatus();
    Poll.refreshActive(); // 활성 탭 즉시 갱신
  } catch (e) {
    showMsg(msg, 'err', '저장 실패: ' + e.message);
  }
}

function showMsg(el, cls, text) {
  el.className = cx('msg', cls);
  el.textContent = text;
  el.style.display = 'block';
}

// ── 초기화 ───────────────────────────────────────────────────────────────
// defer 스크립트는 문서 순서대로 실행되고 DOMContentLoaded보다 먼저 끝난다.
// 탭 모듈들의 Poll.register가 모두 끝난 뒤 시작해야 하므로 이벤트에 건다.

Poll.register('*', loadStatus, 10_000);

document.addEventListener('DOMContentLoaded', async () => {
  const status = await loadStatus();
  if (status && !status.configured) openSettings();
  switchTab(location.hash.slice(1) || 'home');
});
