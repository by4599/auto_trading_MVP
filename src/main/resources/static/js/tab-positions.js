// tab-positions.js — 보유종목 탭: 보유 주식 리스트 + 변동값 + 손절선 거리

async function loadPositionsTab() {
  const el = document.getElementById('positionsTabBody');
  if (!el) return;
  try {
    const list = await get('/api/position');
    el.textContent = '';

    if (!list || list.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'empty-state';
      empty.textContent = '보유 중인 주식이 없습니다';
      el.appendChild(empty);
      return;
    }

    const table = document.createElement('table');
    table.className = 'orders-table';

    const thead = document.createElement('thead');
    const hrow = document.createElement('tr');
    ['종목', '수량', '평균단가', '현재가', '전일대비', '평가손익', '손절선'].forEach(h => {
      const th = document.createElement('th');
      th.textContent = h;
      hrow.appendChild(th);
    });
    thead.appendChild(hrow);
    table.appendChild(thead);

    const tbody = document.createElement('tbody');
    let totalPnl = 0;
    let hasPnl = false;

    list.forEach(p => {
      const tr = document.createElement('tr');

      // 손절선까지 3% 이내면 행 경고
      if (p.stopDistancePct != null && p.stopDistancePct <= 3) {
        tr.style.background = 'rgba(210, 153, 34, 0.08)';
      }

      const tdName = document.createElement('td');
      tdName.textContent = (p.stockName || p.stockCode) + ' · ' + p.stockCode;
      tr.appendChild(tdName);

      const tdQty = document.createElement('td');
      tdQty.textContent = p.quantity + '주';
      tr.appendChild(tdQty);

      const tdAvg = document.createElement('td');
      tdAvg.textContent = KRW(p.averagePrice);
      tr.appendChild(tdAvg);

      const tdCur = document.createElement('td');
      tdCur.textContent = p.currentPrice != null ? KRW(p.currentPrice) : '—';
      tr.appendChild(tdCur);

      const tdChange = document.createElement('td');
      if (p.changeAmount != null) {
        tdChange.className = priceClass(p.changeAmount);
        tdChange.textContent = SIGN(p.changeAmount) + KRW(p.changeAmount) + ' (' + PCT(p.changeRate) + ')';
      } else {
        tdChange.textContent = '—';
      }
      tr.appendChild(tdChange);

      const tdPnl = document.createElement('td');
      if (p.unrealizedPnl != null) {
        totalPnl += p.unrealizedPnl;
        hasPnl = true;
        tdPnl.className = priceClass(p.unrealizedPnl);
        tdPnl.textContent = SIGN(p.unrealizedPnl) + KRW(p.unrealizedPnl) + ' (' + PCT(p.unrealizedPnlRate) + ')';
      } else {
        tdPnl.textContent = '—';
      }
      tr.appendChild(tdPnl);

      const tdStop = document.createElement('td');
      if (p.stopPrice != null) {
        tdStop.textContent = KRW(p.stopPrice) +
          (p.stopDistancePct != null ? ' (-' + p.stopDistancePct.toFixed(1) + '%)' : '');
        if (p.stopDistancePct != null && p.stopDistancePct <= 3) {
          tdStop.style.color = 'var(--yellow)';
          tdStop.style.fontWeight = '600';
        }
      } else {
        tdStop.textContent = '미장착';
        tdStop.style.color = 'var(--subtle)';
      }
      tr.appendChild(tdStop);

      tbody.appendChild(tr);
    });

    // 합계 행
    if (hasPnl) {
      const tr = document.createElement('tr');
      const tdLabel = document.createElement('td');
      tdLabel.textContent = '합계';
      tdLabel.style.fontWeight = '700';
      tr.appendChild(tdLabel);
      for (let i = 0; i < 4; i++) tr.appendChild(document.createElement('td'));
      const tdTotal = document.createElement('td');
      tdTotal.className = priceClass(totalPnl);
      tdTotal.style.fontWeight = '700';
      tdTotal.textContent = SIGN(totalPnl) + KRW(totalPnl);
      tr.appendChild(tdTotal);
      tr.appendChild(document.createElement('td'));
      tbody.appendChild(tr);
    }

    table.appendChild(tbody);
    el.appendChild(table);
  } catch { /* 유지 */ }
}

Poll.register('positions', loadPositionsTab, 10_000);
