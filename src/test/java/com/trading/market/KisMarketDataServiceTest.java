package com.trading.market;

import com.trading.NotificationService;
import com.trading.risk.TradingStatusManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 일봉 N개 조회 — KIS 일봉 차트는 호출당 약 100행이 상한이다.
 *
 * <p>2026-10-01 발견: 이 메서드가 한 번만 호출하고 끝나서, 125봉을 달라는 전략(이평 정배열·돈치안)이
 * 100봉 미만을 받고 "표본 부족"으로 영원히 신호를 내지 못했다(07-04~ 90일간 이평돌파 칸 매매 0건).
 * 부족하면 커서를 과거로 옮겨 이어 받되, 첫 페이지로 충분한 요청(ATR 15봉)은 예전처럼 1회만 부른다.
 */
@DisplayName("KisMarketDataService.getDailyCandles — 100행 상한 페이지네이션")
class KisMarketDataServiceTest {

    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String TOKEN_JSON = "{\"access_token\":\"t\",\"expires_in\":86400}";
    private static final String DAILY_PATH = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice";

    /** 운영 코드가 LocalDate.now()(시스템 시계)를 쓰므로 테스트도 같은 기준을 쓴다 */
    private final LocalDate today = LocalDate.now();

    private MockRestServiceServer apiMock;
    private KisMarketDataService sut;

    @BeforeEach
    void setUp() {
        KisProperties props = new KisProperties();
        props.setBaseUrl("https://openapivts.koreainvestment.com:29443");
        props.setAppkey("k");
        props.setSecretkey("s");
        props.setAccountNo("50000000-01");

        RestClient.Builder tokenBuilder = RestClient.builder();
        MockRestServiceServer tokenMock = MockRestServiceServer.bindTo(tokenBuilder).build();
        tokenMock.expect(ExpectedCount.once(), requestTo("/oauth2/tokenP"))
                .andRespond(withSuccess(TOKEN_JSON, MediaType.APPLICATION_JSON));

        RestClient.Builder apiBuilder = RestClient.builder();
        apiMock = MockRestServiceServer.bindTo(apiBuilder).build();

        KisApiClient kis = new KisApiClient(props, new TradingStatusManager(), mock(NotificationService.class),
                new KisRateLimiter(10_000), Clock.systemDefaultZone(), tokenBuilder, apiBuilder);
        sut = new KisMarketDataService(kis);
    }

    /** 최신→과거 순 KIS 응답 본문 — startDaysAgo일 전부터 count개, 하루씩 과거로 */
    private static String page(LocalDate today, int startDaysAgo, int count) {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            LocalDate d = today.minusDays(startDaysAgo + i);
            double px = 10_000 + (startDaysAgo + i);
            rows.add(String.format(
                    "{\"stck_bsop_date\":\"%s\",\"stck_oprc\":\"%.0f\",\"stck_hgpr\":\"%.0f\","
                            + "\"stck_lwpr\":\"%.0f\",\"stck_clpr\":\"%.0f\",\"acml_vol\":\"1000\"}",
                    d.format(YMD), px, px + 10, px - 10, px));
        }
        return "{\"output2\":[" + String.join(",", rows) + "]}";
    }

    private void expectPage(LocalDate endDate, String body) {
        apiMock.expect(ExpectedCount.once(), requestTo(containsString(DAILY_PATH)))
                .andExpect(queryParam("FID_INPUT_DATE_2", endDate.format(YMD)))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("첫 페이지로 충분하면(ATR 15봉) 1회만 부른다 — 기존 동작 그대로")
    void single_call_when_first_page_suffices() {
        expectPage(today, page(today, 0, 100));  // 오늘(미완성) + 과거 99봉

        List<Candle> candles = sut.getDailyCandles("005930", 15);

        apiMock.verify();
        assertThat(candles).hasSize(15);
        assertThat(candles.get(14).date()).isEqualTo(today.minusDays(1));   // 최신 = 어제(오늘 제외)
        assertThat(candles.get(0).date()).isEqualTo(today.minusDays(15));
    }

    @Test
    @DisplayName("125봉 요청 — 첫 페이지(100행)가 모자라면 가장 오래된 날 전날부터 이어 받는다")
    void paginates_when_first_page_is_short() {
        expectPage(today, page(today, 0, 100));                       // 오늘 + 99봉
        expectPage(today.minusDays(100), page(today, 100, 100));      // 그다음 과거 100봉

        List<Candle> candles = sut.getDailyCandles("005930", 125);

        apiMock.verify();
        assertThat(candles).hasSize(125);
        assertThat(candles.get(124).date()).isEqualTo(today.minusDays(1));
        assertThat(candles.get(0).date()).isEqualTo(today.minusDays(125));
        for (int i = 1; i < candles.size(); i++) {
            assertThat(candles.get(i).date()).isAfter(candles.get(i - 1).date());  // 과거→최신, 중복 없음
        }
        assertThat(candles).noneMatch(c -> c.date().equals(today));
    }

    @Test
    @DisplayName("과거 이력이 바닥나면(빈 페이지) 있는 만큼만 돌려준다 — 예외 없이")
    void stops_when_history_runs_out() {
        expectPage(today, page(today, 1, 60));                  // 신규 상장 등 — 60봉뿐
        expectPage(today.minusDays(61), "{\"output2\":[]}");

        List<Candle> candles = sut.getDailyCandles("005930", 125);

        apiMock.verify();
        assertThat(candles).hasSize(60);
        assertThat(candles.get(59).date()).isEqualTo(today.minusDays(1));
    }

    @Test
    @DisplayName("첫 응답부터 비면 예외 — 호출 측이 '조회 실패'로 처리하는 기존 계약 유지")
    void throws_when_first_page_is_empty() {
        expectPage(today, "{\"output2\":[]}");

        assertThatThrownBy(() -> sut.getDailyCandles("005930", 15))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("일봉 조회 실패");
    }
}
