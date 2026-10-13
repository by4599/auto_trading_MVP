package com.trading.backtest;

import com.trading.market.MinuteCandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * 운영(paper) DB의 candle_history에서 분봉을 <b>읽기만</b> 하는 원천 구현.
 *
 * H2 파일 DB에 {@code AUTO_SERVER=TRUE}가 걸려 있어 모의투자 앱이 켜져 있어도 동시 접속이 된다.
 * 쓰기는 절대 하지 않는다 — 운영 DB는 잔고·주문의 원본이므로 백테스트가 건드리면 안 된다.
 */
@Component
@Profile("backtest")
public class JdbcMinuteCandleSource implements MinuteCandleSource {

    private static final Logger log = LoggerFactory.getLogger(JdbcMinuteCandleSource.class);

    private static final String SELECT_MINUTES = """
            SELECT stock_code, candle_date, candle_time, open_price, high_price,
                   low_price, close_price, volume
            FROM candle_history
            WHERE timeframe = 'MINUTE'
            ORDER BY stock_code, candle_date, candle_time
            """;

    private final String url;
    private final String username;
    private final String password;

    public JdbcMinuteCandleSource(
            @Value("${backtest.paper-db-url:jdbc:h2:file:./trading-db;AUTO_SERVER=TRUE}") String url,
            @Value("${backtest.paper-db-username:sa}") String username,
            @Value("${backtest.paper-db-password:}") String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    @Override
    public List<Row> fetchAll() {
        List<Row> rows = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, username, password);
             PreparedStatement ps = conn.prepareStatement(SELECT_MINUTES);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                rows.add(new Row(rs.getString("stock_code"), new MinuteCandle(
                        rs.getDate("candle_date").toLocalDate(),
                        rs.getTime("candle_time").toLocalTime(),
                        rs.getDouble("open_price"), rs.getDouble("high_price"),
                        rs.getDouble("low_price"), rs.getDouble("close_price"),
                        rs.getLong("volume"))));
            }
        } catch (Exception e) {
            // 운영 DB가 없거나(첫 실행) 접속이 막혀도 백테스트 전체를 죽이지 않는다
            log.warn("[MinuteImport] 운영 DB 분봉 조회 실패 — 빈 결과로 진행: {} ({})", e.getMessage(), url);
            return List.of();
        }
        log.info("[MinuteImport] 운영 DB 분봉 {}건 조회 ({})", rows.size(), url);
        return List.copyOf(rows);
    }
}
