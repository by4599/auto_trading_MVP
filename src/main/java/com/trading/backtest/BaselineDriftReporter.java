package com.trading.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 회귀 앵커 대조 — 이번 실행 결과를 기준선 yml(정본)과 맞춰 보고 로그·리포트에 남긴다.
 *
 * <p>왜 필요한가: 앵커 수치가 문서 여러 곳에 손으로 옮겨져 있으면 하나만 고쳐도 조용히
 * 어긋난다(2026-07 §14.1 드리프트). 실행이 스스로 정본과 대조해 결과를 찍으면, 사람이
 * 숫자를 기억하거나 눈으로 맞춰 볼 일이 없어진다.
 *
 * <p><b>드리프트는 실패가 아니다</b> — 판정 창·유니버스를 일부러 바꿔 돌리는 정상 사용
 * (§14.3 약세장 창 등)이 있으므로 경고·기록까지가 이 단계의 책임이다. 창이 다르면 대조
 * 자체를 생략한다(다른 실험을 앵커와 비교하는 것은 무의미하다).
 */
@Component
@Profile("backtest")
public class BaselineDriftReporter {

    private static final Logger log = LoggerFactory.getLogger(BaselineDriftReporter.class);

    private final BaselineStore store;

    public BaselineDriftReporter(BaselineStore store) {
        this.store = store;
    }

    /**
     * 대조 결과 마크다운 절 (앵커가 없으면 빈 문자열 — 첫 기록 실행은 조용히 넘어간다).
     *
     * @param profileName 기준선 대상(=첫 합격) 프로필. 합격이 없으면 null
     * @param current     그 프로필의 검증 구간 집계. 합격이 없으면 null
     */
    public String render(String baselineSlug, LocalDate from, LocalDate to,
                         String profileName, BacktestMetrics current) {
        if (baselineSlug == null) return "";
        Optional<BaselineSnapshot> anchor = store.load(baselineSlug);
        if (anchor.isEmpty()) return "";

        return "## 회귀 앵커 대조 — docs/" + BaselineStore.fileName(baselineSlug) + "\n\n"
                + body(anchor.get(), from, to, profileName, current) + "\n";
    }

    private String body(BaselineSnapshot anchor, LocalDate from, LocalDate to,
                        String profileName, BacktestMetrics current) {
        if (current == null) {
            log.warn("[Baseline] ⚠ 합격 프로필이 없어 앵커 대조를 못 했다 (앵커 트레이드 {} · PF {})",
                    anchor.trades(), anchor.profitFactor());
            return String.format("- 이번 실행에는 합격 프로필이 없어 대조하지 않았다"
                    + " (앵커: 트레이드 %d · PF %s)%n", anchor.trades(), anchor.profitFactor());
        }
        BaselineSnapshot now = BaselineSnapshot.of(from, to, current);
        if (!anchor.sameWindow(now)) {
            log.info("[Baseline] 판정 창이 앵커({}~{})와 달라 대조 생략 — 이번 실행 {}~{}",
                    anchor.from(), anchor.to(), from, to);
            return String.format("- 판정 창이 앵커(%s ~ %s)와 달라 대조를 생략했다"
                            + " — 이번 실행 %s ~ %s (창을 바꾼 실행은 정상 사용이다)%n",
                    anchor.from(), anchor.to(), from, to);
        }
        return verdict(anchor.compare(now), profileName);
    }

    private String verdict(List<BaselineSnapshot.MetricDiff> rows, String profileName) {
        List<String> drifted = rows.stream()
                .filter(BaselineSnapshot.MetricDiff::drifted)
                .map(BaselineSnapshot.MetricDiff::arrow).toList();

        StringBuilder md = new StringBuilder();
        md.append(drifted.isEmpty()
                ? String.format("- ✅ 앵커와 일치 (대조 프로필: %s)%n", profileName)
                : String.format("- ⚠ **앵커 드리프트 %d건** (대조 프로필: %s) — %s%n",
                        drifted.size(), profileName, String.join(", ", drifted)));
        md.append("- 드리프트는 그 자체로 실패가 아니다: 창·유니버스·비용을 바꾼 실행이면 정상,"
                + " 같은 조건인데 값이 다르면 데이터 커버리지나 코드 변경을 의심할 것\n\n");
        md.append("| 지표 | 앵커 | 이번 실행 | 판정 |\n|---|---|---|---|\n");
        for (BaselineSnapshot.MetricDiff row : rows) {
            md.append(String.format("| %s | %s | %s | %s |%n",
                    row.metric(), row.anchor(), row.current(), row.drifted() ? "⚠ 다름" : "✅"));
        }

        if (drifted.isEmpty()) {
            log.info("[Baseline] ✅ 앵커 재현 확인 — {} (기준선 yml과 5지표 전부 일치)", profileName);
        } else {
            log.warn("[Baseline] ⚠ 앵커 드리프트 {}건 — {} ({})",
                    drifted.size(), profileName, String.join(", ", drifted));
        }
        return md.toString();
    }
}
