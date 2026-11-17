// settings-modal.js — ⚙ 설정 창의 화면 뼈대 (2026-09-22 분리)
//
// index.html이 300줄 상한을 크게 넘어서(364줄) 설정 창 113줄을 이 파일로 옮겼다.
// 아래 글자는 index.html에 있던 것을 한 글자도 바꾸지 않고 그대로 옮긴 것이다.
//
// ⚠ 이 파일만 defer가 아니다 — 브라우저가 문서를 읽는 도중에 바로 실행되어
//    원래 설정 창이 있던 그 자리에 화면 조각을 끼워 넣는다.
//    core.js가 화면이 뜨자마자(DOMContentLoaded) openSettings()를 부를 수 있어
//    그때 #overlay가 이미 있어야 한다. 나중에 불러와 끼워 넣는 방식(fetch)은
//    순서가 어긋나서 설정 창을 못 여는 사고가 난다.

document.currentScript.insertAdjacentHTML('beforebegin', `
<!-- ── 설정 모달 ── -->
<div class="overlay" id="overlay" onclick="overlayClick(event)">
  <div class="modal">
    <div class="modal-hdr">
      <h2>⚙ 설정</h2>
      <button class="btn-close" onclick="closeSettings()">✕</button>
    </div>

    <div class="modal-tabs">
      <button class="modal-tab-btn active" id="modalTabBtnApi"    onclick="switchModalTab('api')">API 키</button>
      <button class="modal-tab-btn"        id="modalTabBtnParams" onclick="switchModalTab('params')">투자 파라미터</button>
    </div>

    <div id="modalTabApi">
    <div class="section-hdr">📋 현재 설정 상태</div>
    <div id="settingStatusRows"><span style="color:var(--subtle);font-size:0.8rem">조회 중...</span></div>

    <hr class="divider">

    <div class="section-hdr">🏦 KIS Open API 자격증명</div>
    <p class="hint">
      <a href="https://apiportal.koreainvestment.com" target="_blank">KIS Developers</a>에서
      <strong style="color:var(--text)">모의투자</strong> 앱키를 발급받으세요.
    </p>

    <div class="field">
      <label>KIS_APPKEY</label>
      <div class="input-wrap">
        <input type="password" id="appkey" placeholder="PSxxxxxxxxxxxxxxxxxx...">
        <span class="eye" onclick="toggle('appkey')">👁</span>
      </div>
    </div>
    <div class="field">
      <label>KIS_SECRETKEY</label>
      <div class="input-wrap">
        <input type="password" id="secretkey" placeholder="시크릿키 입력">
        <span class="eye" onclick="toggle('secretkey')">👁</span>
      </div>
    </div>
    <div class="field">
      <label>KIS_ACCOUNT_NO</label>
      <input type="text" id="account_no" placeholder="50000000-01">
      <div class="field-hint">계좌번호-상품코드 형식 (예: 50000000-01)</div>
    </div>

    <button class="btn-save green" onclick="saveKis()">저장 (재시작 불필요)</button>
    <div class="msg" id="kisMsg"></div>

    <hr class="divider">

    <div class="section-hdr">💬 텔레그램 알림 <span style="color:var(--subtle);font-weight:400">(선택)</span></div>
    <p class="hint">비워두면 알림 없이 거래만 진행됩니다.</p>

    <div class="field">
      <label>TELEGRAM_BOT_TOKEN</label>
      <div class="input-wrap">
        <input type="password" id="tg_token" placeholder="@BotFather에서 발급">
        <span class="eye" onclick="toggle('tg_token')">👁</span>
      </div>
    </div>
    <div class="field">
      <label>TELEGRAM_CHAT_ID</label>
      <input type="text" id="tg_chat" placeholder="123456789">
    </div>

    <button class="btn-save ghost" onclick="saveTelegram()">텔레그램 저장</button>
    <div class="msg" id="tgMsg"></div>

    <hr class="divider">

    <div class="section-hdr">📋 DART 공시 API <span style="color:var(--subtle);font-weight:400">(선택)</span></div>
    <p class="hint">
      <a href="https://opendart.fss.or.kr" target="_blank">DART Open API</a>에서 무료 발급.
      비워두면 공시 수집 없이 뉴스만 수집됩니다.
    </p>
    <div class="field">
      <label>DART_API_KEY</label>
      <div class="input-wrap">
        <input type="password" id="dart_key" placeholder="40자리 인증키">
        <span class="eye" onclick="toggle('dart_key')">👁</span>
      </div>
    </div>
    <button class="btn-save ghost" onclick="saveDart()">DART 저장</button>
    <div class="msg" id="dartMsg"></div>

    <hr class="divider">

    <div class="section-hdr">💓 데드맨 스위치 <span style="color:var(--subtle);font-weight:400">(선택)</span></div>
    <p class="hint">
      <a href="https://healthchecks.io" target="_blank">healthchecks.io</a> 등에서 만든 ping URL.
      5분마다 현재 모드·보유 종목 수를 전송합니다 — 앱이 죽으면 이 신호도 끊기므로
      외부 서비스 쪽에서 무응답 경고를 설정해야 완성됩니다. 비워두면 전송하지 않습니다.
    </p>
    <div class="field">
      <label>HEARTBEAT_URL</label>
      <input type="text" id="heartbeat_url" placeholder="https://hc-ping.com/xxxxxxxx-xxxx-...">
    </div>
    <button class="btn-save ghost" onclick="saveHeartbeat()">데드맨 스위치 저장</button>
    <div class="msg" id="heartbeatMsg"></div>
    </div><!-- /modalTabApi -->

    <div id="modalTabParams" style="display:none">
      <p class="hint">
        투자 기준을 세부 조정합니다. 각 항목은 안전 범위 안에서만 저장되며,
        저장 즉시 반영됩니다 (재시작 불필요).
      </p>
      <div id="paramGroups"><div class="empty-state">불러오는 중...</div></div>
      <button class="btn-save green" onclick="saveParams()">파라미터 저장</button>
      <button class="btn-save ghost" onclick="resetParams()">기본값 원복</button>
      <div class="msg" id="paramMsg"></div>
    </div><!-- /modalTabParams -->
  </div>
</div>
`);
