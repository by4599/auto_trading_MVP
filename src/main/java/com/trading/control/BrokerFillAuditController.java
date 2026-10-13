package com.trading.control;

import com.trading.order.BrokerFillHistoryClient;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 브로커 체결 원장 조회 (읽기 전용) — 우리 DB와 증권사 기록을 대조하기 위한 창구.
 *
 * <p>존재 이유: 체결가가 0으로 종결된 과거 매도들(2026-08-07 실측 37건)의 <b>실제 체결가가
 * 브로커 원장에는 남아 있다.</b> 이 창구로 그 값을 꺼내 슬리피지·실현손익을 사후 계산한다.
 *
 * <p>주문을 내지 않는다. DB를 쓰지 않는다. 조회 결과를 그대로 돌려줄 뿐이다.
 */
@RestController
@RequestMapping("/api/trading")
@Profile("paper")
public class BrokerFillAuditController {

    private final BrokerFillHistoryClient fillHistoryClient;

    public BrokerFillAuditController(BrokerFillHistoryClient fillHistoryClient) {
        this.fillHistoryClient = fillHistoryClient;
    }

    @GetMapping("/broker-fills")
    public Map<String, Object> brokerFills(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        BrokerFillHistoryClient.FillPage page = fillHistoryClient.fetch(from, to);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from.toString());
        body.put("to", to.toString());
        body.put("apiError", page.apiError());
        body.put("count", page.byOrderNo().size());
        body.put("fills", page.byOrderNo());
        return body;
    }
}
