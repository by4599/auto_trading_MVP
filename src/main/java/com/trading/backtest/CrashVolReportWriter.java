package com.trading.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 약세장 방어 측정(BACKLOG 2026-07-24 탐색) 리포트 — 급락 직후 저변동성 종목의 전방수익률.
 *
 * <p>거버넌스 기준선 yml은 쓰지 않는다(측정 전용). 표본은 이벤트 단위로 접힌 상태
 * (n = 급락 이벤트 수, 종목 수 아님 — {@link LowVolCrashBacktester} 참고).
 */
@Component
@Profile("backtest")
public class CrashVolReportWriter {

    private static final Logger log = LoggerFactory.getLogger(CrashVolReportWriter.class);

    public Path writeLowVolCrashReport(LowVolCrashBacktester.CrashVolReport report) {
        StringBuilder md = new StringBuilder();
        appendHeader(md, report);
        appendThresholdCounts(md, report);
        appendForwardReturns(md, report);
        appendReadingGuide(md);
        return save(md.toString());
    }

    private void appendHeader(StringBuilder md, LowVolCrashBacktester.CrashVolReport report) {
        md.append("# 약세장 방어 측정 — 급락 직후 저변동성 종목의 전방수익률\n\n");
        md.append("측정 실험(전략 채택 아님). 거버넌스 기준선·레지스트리에 아무것도 기록하지 않는다.\n\n");
        md.append(String.format("- 실행: %s%n- 기간: %s ~ %s%n- 유니버스: %d종목%n",
                LocalDateTime.now(), report.from(), report.to(), report.universeSize()));
        md.append(String.format("- 급락 정의: 트레일링 10거래일 KOSPI 수익률 ≤ %.0f%% · "
                        + "쿨다운 %d거래일(≥최대 호라이즌 D+%d — 앵커 전방창 중첩 제거) · "
                        + "버킷당 최소 %d종목%n",
                report.threshold() * 100, report.cooldownDays(),
                LowVolCrashBacktester.MAX_HORIZON, report.minCoverage()));
        for (LowVolCrashBacktester.WindowStats w : report.windows()) {
            md.append(String.format("- 변동성 창 **%s**: %s%n", w.window().code(), w.window().label()));
        }
    }

    private void appendThresholdCounts(StringBuilder md, LowVolCrashBacktester.CrashVolReport report) {
        md.append("\n## 급락 임계치별 이벤트 개수 (검정력 정직성)\n\n");
        md.append("| 임계치 | 앵커 이벤트 수 |\n|---|---|\n");
        report.thresholdCounts().forEach((t, c) ->
                md.append(String.format("| %.0f%% | %d |%n", t * 100, c)));
        md.append(String.format("%n본 통계는 임계 %.0f%% (앵커 %d건)로 낸다.%n%n",
                report.threshold() * 100, report.anchorCount()));
    }

    private void appendForwardReturns(StringBuilder md, LowVolCrashBacktester.CrashVolReport report) {
        md.append("## 전방수익률 — 이벤트당 버킷 중앙값을 이벤트 축으로 집계\n\n");
        md.append("각 급락 이벤트에서 종목을 저/고 변동성으로 갈라 이벤트당 중앙값 하나로 접은 뒤, ")
          .append("그 이벤트 중앙값들의 중앙값(median)을 표시한다 (n = 이벤트 수).\n");
        md.append("**변동성 창 정의를 바꿔 두 번 잰다** — 같은 앵커·같은 전방수익률에 ")
          .append("버킷팅 기준만 다르다. `-`는 표본이 없어 측정 못 함(0%가 아님).\n\n");
        for (LowVolCrashBacktester.WindowStats w : report.windows()) {
            md.append(String.format("### %s 기준 버킷팅 — %s%n%n", w.window().code(), w.window().label()));
            md.append("| 호라이즌 | 저변동성 중앙값 | 고변동성 중앙값 | KOSPI | 저−고 차 | 이벤트 n | (참고)중 |\n");
            md.append("|---|---|---|---|---|---|---|\n");
            for (LowVolCrashBacktester.HorizonStat h : w.horizons()) {
                md.append(String.format("| D+%d | %s | %s | %s | %s | %d | %s |%n",
                        h.horizon(), ReportFormat.signedPct(h.lowVol()),
                        ReportFormat.signedPct(h.highVol()),
                        ReportFormat.signedPct(h.kospi()), ReportFormat.lowMinusHighText(h),
                        h.events(), ReportFormat.signedPct(h.midVol())));
            }
            md.append('\n');
        }
    }

    private void appendReadingGuide(StringBuilder md) {
        md.append("## 판독 지침 (정직하게)\n\n");
        md.append("- ⓪ **두 표가 서로 다르면, 물었던 질문의 답은 PRE 표다.** PRE는 ")
          .append("'급락 **전까지** 조용했던 종목'을, DURING은 '이번 급락에서 **덜 맞은** 종목'을 ")
          .append("갈라낸다. DURING만 보면 되돌림(덜 맞은 종목이 되돌아오는 것)을 ")
          .append("'저변동성 효과'로 오독하게 된다. 두 표가 비슷하면 두 정의가 실무상 같다는 뜻.\n");
        md.append("- ① **덜 다침**: 단기(D+5/10)에서 저변동성 초과수익≥0 & 지수(KOSPI)가 음수로 읽힌다.\n");
        md.append("- ② **급등**: 장기(D+20/60)에서 저변동성 중앙값이 크고 **고변동성보다 커야** 성립. ")
          .append("역사적으로 최대 반등은 고변동성에서 나오는 경향이라 이 칸(저−고 차)이 핵심 검증 포인트다.\n");
        md.append("- ③ **생존편향**: 유니버스는 오늘 살아있는 대형주라 '저변동성인데 망한 종목'이 빠져 ")
          .append("저변동성에 유리하게 편향될 수 있다.\n");
        md.append("- ④ **이벤트 수가 적으면 통계적 결론 금지** — 방향 참고만. ")
          .append("2023~2026은 대체로 우호장이고, 게다가 쿨다운을 최대 호라이즌(60거래일)까지 올려 ")
          .append("앵커를 더 솎아냈다(전방창이 겹치면 n이 유효표본을 부풀리기 때문). ")
          .append("이벤트가 적게 나오는 건 이 창과 이 정의의 한계지 결함이 아니다 — 낮은 검정력을 숨기지 않는다.\n");
        md.append("- ⑤ 일봉 근사 한계 — 장중 경로·정확한 진입시각은 무시한다.\n");
        md.append("- ⑥ PRE 표는 앵커 -30거래일 이력이 필요해, 이력이 짧은 종목이 그 이벤트의 ")
          .append("PRE 버킷에서만 빠진다(DURING 표는 기존대로). 두 표의 이벤트 n이 다를 수 있는 이유다.\n");
    }

    private Path save(String markdown) {
        try {
            Path dir = Path.of("logs", "backtest");
            Files.createDirectories(dir);
            Path file = dir.resolve("REPORT-CRASHVOL-" + LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".md");
            Files.writeString(file, markdown);
            log.info("[Report] 약세장 방어 측정 리포트 저장: {}", file.toAbsolutePath());
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("약세장 방어 측정 리포트 저장 실패", e);
        }
    }
}
