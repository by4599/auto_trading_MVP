package com.trading.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * B-4 ④ 이벤트 백테스트 리포트 (logs/backtest/EVENT-REPORT-*.md).
 *
 * <p>표기는 CANDIDATE까지다 — 매매 신호 승격(PROMOTED)은 사람이 판단한다(게이트 G2).
 */
@Component
@Profile("backtest")
public class EventReportWriter {

    private static final Logger log = LoggerFactory.getLogger(EventReportWriter.class);

    private final Clock clock;

    public EventReportWriter(Clock clock) {
        this.clock = clock;
    }

    public void write(EventReportData data) {
        StringBuilder sb = new StringBuilder();
        appendHeader(sb, data);
        appendEventTable(sb, data);
        appendGroupedTable(sb, data);
        appendSpilloverTable(sb, data);
        appendTriggerTable(sb, data);
        save(sb.toString());
    }

    private void appendHeader(StringBuilder sb, EventReportData data) {
        sb.append("# 이벤트 백테스트 리포트 (B-4) — 공시 유형별 주가 반응 통계\n\n");
        sb.append("- 실행: ").append(LocalDateTime.now(clock)).append('\n');
        sb.append("- 기간: ").append(data.from()).append(" ~ ").append(data.to()).append('\n');
        sb.append("- 종목: ").append(String.join(", ", data.symbols())).append('\n');
        sb.append("- 진입 관례: 공시일 **다음 거래일 시가** (공시 시각 불명 — 선견 편향 차단)\n");
        sb.append("- v2 통계 교정: 수익률은 **시장별 지수(KOSPI/KOSDAQ) 대비 초과수익**, 같은 종목·유형 ")
          .append(EventStatsBacktester.CLUSTER_WINDOW_TRADING_DAYS)
          .append("거래일 내 클러스터는 첫 건만 채택\n");
        sb.append("- 크기 조건화: ").append(EventStatsBacktester.BIG_CONTRACT_TYPE)
          .append(" = 계약금액이 최근 매출액의 ")
          .append(String.format("%.0f%%", EventStatsBacktester.BIG_CONTRACT_MIN_SALES_RATIO_PCT))
          .append(" 이상인 공급계약 (원문 파싱)\n");
        sb.append("- CANDIDATE 조건: 표본 ≥ ").append(EventCandidateCriteria.MIN_SAMPLES_FOR_CANDIDATE)
          .append("건 AND D+5 p25 > 왕복 비용 ")
          .append(String.format("%.2f%%", BacktestCosts.ROUND_TRIP_COST * 100)).append('\n');
        sb.append("- ⚠ 매매 신호 승격(PROMOTED)은 사람이 판단한다 — 이 리포트는 CANDIDATE 표기까지만\n\n");
    }

    private void appendEventTable(StringBuilder sb, EventReportData data) {
        sb.append("| 유형 | 표본 | D+5 승률 | D+1 중앙값 | D+5 중앙값 | D+5 p25 | D+10 중앙값 | D+20 중앙값 | D+20 p25 | 판정 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (EventStatsBacktester.EventStat s : data.stats()) {
            sb.append(statRow(s));
        }
        sb.append("\n※ 표본이 작은 유형(<30)은 통계가 노이즈다 — 수집이 쌓일수록 신뢰도가 오른다.\n");
    }

    private void appendGroupedTable(StringBuilder sb, EventReportData data) {
        if (data.groupedStats().isEmpty()) return;
        sb.append("\n## 시가총액별 재집계 (LARGE vs MIDSMALL)\n\n");
        sb.append("- 가설: 대형주는 개별 공시에 둔감하다는 통설(§8) — 유형을 그대로 두고 ")
          .append("종목만 대형주(LARGE)/중소형주(MIDSMALL)로 나눠 같은 CANDIDATE 기준으로 재본다.\n");
        sb.append("- LARGE = event-themes의 large-benchmark + B-3 6종목, ")
          .append("MIDSMALL = semi-chain·battery·bio·game·robot\n\n");
        sb.append("| 유형:그룹 | 표본 | D+5 승률 | D+1 중앙값 | D+5 중앙값 | D+5 p25 | D+10 중앙값 | D+20 중앙값 | D+20 p25 | 판정 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (EventStatsBacktester.EventStat s : data.groupedStats()) {
            sb.append(statRow(s));
        }
    }

    private static String statRow(EventStatsBacktester.EventStat s) {
        return String.format("| %s | %d | %.1f%% | %s | %s | %s | %s | %s | %s | %s |%n",
                s.eventType(), s.samples(), s.winRateD5() * 100,
                pct(s.at(1).median()), pct(s.at(5).median()), pct(s.at(5).p25()),
                pct(s.at(10).median()), pct(s.at(20).median()), pct(s.at(20).p25()),
                EventCandidateCriteria.meets(s) ? "🟡 CANDIDATE" : "RECORDED");
    }

    private void appendSpilloverTable(StringBuilder sb, EventReportData data) {
        if (data.spillover().isEmpty()) return;
        sb.append("\n## 테마 파급(Spillover) — 앵커 공시 → 밸류체인 D+N 초과수익\n\n");
        sb.append("- 가설: 앵커 대형주의 호재가 같은 테마 중소형주에 시차를 두고 파급된다 (방법론 파일럿)\n");
        sb.append("- 표본 단위: **앵커 이벤트 1건** — 체인 종목 초과수익의 횡단면 중앙값으로 접음 ")
          .append("(교차 상관 부풀림 차단, 이벤트당 최소 커버리지 ")
          .append(SpilloverStatsBacktester.MIN_CHAIN_COVERAGE).append("종목)\n");
        sb.append("- 해석: D+5/D+10 중앙값이 양(+)이고 D+1이 작으면 \"천천히 스며드는\" ")
          .append("파급 — 진입 시차 여지가 있다는 뜻\n\n");
        sb.append("| 테마 | 앵커 이벤트 유형 | 체인 | 표본 | D+5 승률 | D+1 중앙값 | D+5 중앙값 | D+5 p25 | D+10 중앙값 | D+20 중앙값 | 판정 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (SpilloverStatsBacktester.SpilloverStat s : data.spillover()) {
            sb.append(String.format("| %s | %s | %d | %d | %.1f%% | %s | %s | %s | %s | %s | %s |%n",
                    s.theme(), s.eventType(), s.chainSize(), s.samples(), s.winRateD5() * 100,
                    pct(s.at(1).median()), pct(s.at(5).median()), pct(s.at(5).p25()),
                    pct(s.at(10).median()), pct(s.at(20).median()),
                    EventCandidateCriteria.meets(s.samples(), s.at(5)) ? "🟡 CANDIDATE" : "RECORDED"));
        }
    }

    private void appendTriggerTable(StringBuilder sb, EventReportData data) {
        if (data.triggers().isEmpty()) return;
        sb.append("\n## 진입 트리거 실험 (v1 일봉 근사) — 파급 이벤트에서 언제 타는가\n\n");
        sb.append("- 같은 이벤트 표본에 진입 방식 3종 적용, 청산은 전 트리거 공통 **이벤트 D+")
          .append(EntryTriggerBacktester.EXIT_HORIZON_TRADING_DAYS)
          .append(" 종가** 고정 (진입 타이밍만 격리 비교)\n");
        sb.append("- IMMEDIATE = 공시 다음날 시가 (파급 통계와 동일 기준선) / ")
          .append("PULLBACK_REBOUND = 눌림 후 눌림일 고가 돌파 (상승 반등 확인) / ")
          .append("BREAKOUT = 이벤트일 고가 돌파 (모멘텀 확인)\n");
        sb.append("- 트리거 완성 기한 D+").append(EntryTriggerBacktester.FIRE_WINDOW_TRADING_DAYS)
          .append(", 돌파 진입가는 max(시가, 기준가)로 보수 추정. ")
          .append("발동률 = 적격 체인 멤버 중 트리거 완성 비율 (낮으면 기회 자체가 드묾)\n");
        sb.append("- 비교 기준: 트리거 중앙값이 IMMEDIATE보다 높고 발동률이 지나치게 낮지 ")
          .append("않아야 실전 후보 — 최종 채택은 분봉 정밀 검증 후\n\n");
        sb.append("| 테마 | 앵커 이벤트 유형 | 트리거 | 표본 | 발동률 | 승률 | 중앙값 | p25 |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (EntryTriggerBacktester.TriggerStat t : data.triggers()) {
            sb.append(String.format("| %s | %s | %s | %d | %.0f%% | %.1f%% | %s | %s |%n",
                    t.theme(), t.eventType(), t.trigger(), t.samples(),
                    t.fireRate() * 100, t.winRate() * 100,
                    pct(t.quantiles().median()), pct(t.quantiles().p25())));
        }
    }

    private void save(String markdown) {
        try {
            Path dir = Path.of("logs", "backtest");
            Files.createDirectories(dir);
            Path file = dir.resolve("EVENT-REPORT-" + LocalDateTime.now(clock)
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".md");
            Files.writeString(file, markdown);
            log.info("[EventBacktest] 리포트 저장: {}", file.toAbsolutePath());
        } catch (IOException e) {
            log.error("[EventBacktest] 리포트 저장 실패", e);
        }
    }

    private static String pct(double v) {
        return String.format("%+.2f%%", v * 100);
    }
}
