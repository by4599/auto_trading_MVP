package com.trading.dashboard;

import com.trading.position.DailyEquity;
import com.trading.position.DailyEquityRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 일별 순손익 원장 조회 API.
 *
 * 리포지토리(인터페이스)만 목으로 만들고 컨트롤러는 실객체로 조립한다 (Java 25 Mockito 제약).
 * 날짜 사실관계 (시스템 도구로 검증): 2026-09-14 월 / 2026-09-11 금
 */
@DisplayName("DailyPnlController — 일별 순손익 원장 조회")
class DailyPnlControllerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate MON_0914 = LocalDate.of(2026, 9, 14);
    private static final LocalDate FRI_0911 = LocalDate.of(2026, 9, 11);

    private final DailyEquityRepository equityRepo = mock(DailyEquityRepository.class);
    private final Clock clock =
            Clock.fixed(MON_0914.atTime(16, 0).atZone(KST).toInstant(), KST);
    private final DailyPnlController sut = new DailyPnlController(equityRepo, clock);

    private void givenLedger(DailyEquity... rows) {
        when(equityRepo.findByTradeDateGreaterThanEqualOrderByTradeDateDesc(any(LocalDate.class)))
                .thenReturn(List.of(rows));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstRow(Map<String, Object> result) {
        return ((List<Map<String, Object>>) result.get("rows")).get(0);
    }

    @Test
    @DisplayName("마감된 날 → 순손익·등락률·현금 증감을 함께 돌려준다")
    void returns_net_pnl_and_cash_delta_for_closed_day() {
        DailyEquity closed = DailyEquity.of(FRI_0911, 50_000_000, 49_000_000);
        closed.recordClose(49_650_000, 49_650_000);
        givenLedger(closed);

        Map<String, Object> result = sut.getDailyPnl(30);
        Map<String, Object> row = firstRow(result);

        assertThat(row.get("closed")).isEqualTo(true);
        assertThat(row.get("netPnl")).isEqualTo(-350_000L);
        assertThat(row.get("netPnlPercent")).isEqualTo(-0.7);
        assertThat(row.get("cashDelta")).isEqualTo(650_000L);
        assertThat(result.get("netPnlSum")).isEqualTo(-350_000L);
        assertThat(result.get("closedDays")).isEqualTo(1L);
    }

    @Test
    @DisplayName("마감을 못 찍은 날 → 0이 아니라 null로 비워두고 합계에서 뺀다")
    void unclosed_day_is_null_not_zero() {
        givenLedger(DailyEquity.of(MON_0914, 50_000_000, 49_000_000));

        Map<String, Object> result = sut.getDailyPnl(30);
        Map<String, Object> row = firstRow(result);

        assertThat(row.get("closed")).isEqualTo(false);
        assertThat(row.get("netPnl")).isNull();
        assertThat(row.get("netPnlPercent")).isNull();
        assertThat(row.get("endEquity")).isNull();
        assertThat(result.get("netPnlSum")).isEqualTo(0L);
        assertThat(result.get("closedDays")).isEqualTo(0L);
    }

    @Test
    @DisplayName("예수금 기록이 없던 옛 행 → 현금 증감은 null (0으로 꾸미지 않는다)")
    void missing_start_deposit_yields_null_cash_delta() {
        DailyEquity legacy = DailyEquity.of(FRI_0911, 50_000_000);   // startDeposit 없음
        legacy.recordClose(50_100_000, 50_100_000);
        givenLedger(legacy);

        Map<String, Object> row = firstRow(sut.getDailyPnl(30));

        assertThat(row.get("netPnl")).isEqualTo(100_000L);
        assertThat(row.get("startDeposit")).isNull();
        assertThat(row.get("cashDelta")).isNull();
    }

    @Test
    @DisplayName("days 파라미터는 1~365로 제한된다")
    void days_parameter_is_bounded() {
        givenLedger();

        assertThat(sut.getDailyPnl(0).get("days")).isEqualTo(1);
        assertThat(sut.getDailyPnl(9_999).get("days")).isEqualTo(365);
        assertThat(sut.getDailyPnl(30).get("days")).isEqualTo(30);
    }
}
