package com.trading.backtest;

import com.trading.backtest.EventStatsBacktester.Quantiles;
import com.trading.backtest.LowVolCrashBacktester.Buckets;
import com.trading.backtest.LowVolCrashBacktester.HorizonStat;
import com.trading.backtest.LowVolCrashBacktester.StockSeries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 약세장 방어 측정의 <b>순수 계산부</b> — 변동성·급락 앵커·버킷·전방수익률·표본 접기.
 *
 * <p>DB·프로퍼티 없이 목 없는 단위 검증이 가능하도록 {@link LowVolCrashBacktester}(조회·배너)와
 * 분리했다.
 */
final class CrashVolCalculations {

    private static final Logger log = LoggerFactory.getLogger(CrashVolCalculations.class);

    private CrashVolCalculations() {
    }

    /**
     * 종가 시퀀스의 일간 로그수익률 <b>모표준편차</b>(N으로 나눔). closes는 시간순
     * 21개면 20개 로그수익률을 낸다. 유효 종가가 2개 미만이면 0.
     */
    static double logReturnStdev(List<Double> closes) {
        List<Double> rets = new ArrayList<>();
        for (int i = 1; i < closes.size(); i++) {
            double prev = closes.get(i - 1);
            double cur = closes.get(i);
            if (prev <= 0 || cur <= 0) continue;
            rets.add(Math.log(cur / prev));
        }
        if (rets.size() < 2) return 0.0;
        double mean = rets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = rets.stream().mapToDouble(r -> (r - mean) * (r - mean)).average().orElse(0);
        return Math.sqrt(var);
    }

    /**
     * 급락 앵커 인덱스 — 트레일링 지수 수익률(close[i]/close[i-trailingDays]-1)이 임계치
     * 이하인 날. 한 앵커 채택 후 다음은 최소 cooldownDays 거래일 지나야 인정(겹치는 창 독립).
     */
    static List<Integer> detectAnchorIndices(List<Double> closes, double threshold,
                                             int trailingDays, int cooldownDays) {
        List<Integer> anchors = new ArrayList<>();
        int lastAccepted = -cooldownDays; // 첫 후보가 항상 통과하도록 (뺄셈 오버플로 회피)
        for (int i = trailingDays; i < closes.size(); i++) {
            double prior = closes.get(i - trailingDays);
            double cur = closes.get(i);
            if (prior <= 0 || cur <= 0) continue;
            double ret = cur / prior - 1.0;
            if (ret <= threshold && i - lastAccepted >= cooldownDays) {
                anchors.add(i);
                lastAccepted = i;
            }
        }
        return anchors;
    }

    /**
     * 앵커 탐지에 쓸 시계열 길이 — 오름차순 날짜에서 {@code to} 이하인 개수. 전방 수익률용
     * 여유 데이터({@code to} 이후)가 앵커로 잡혀 실행 시점마다 결과가 달라지는 걸 막는다.
     */
    static int anchorScopeSize(List<LocalDate> dates, LocalDate to) {
        int n = 0;
        for (LocalDate d : dates) {
            if (d.isAfter(to)) break;
            n++;
        }
        return n;
    }

    /**
     * 변동성 오름차순 3등분. 저=하위 1/3, 고=상위 1/3, 중=나머지. n이 3의 배수가 아니면
     * 저·고에 floor(n/3)씩, 중이 잔여를 흡수한다(비교는 저/고만 — 중은 참고).
     */
    static Buckets terciles(Map<String, Double> volBySymbol) {
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(volBySymbol.entrySet());
        sorted.sort(Comparator.comparingDouble(Map.Entry::getValue));
        int n = sorted.size();
        int edge = n / 3;
        List<String> low = new ArrayList<>();
        List<String> mid = new ArrayList<>();
        List<String> high = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String sym = sorted.get(i).getKey();
            if (i < edge) low.add(sym);
            else if (i >= n - edge) high.add(sym);
            else mid.add(sym);
        }
        return new Buckets(low, mid, high);
    }

    /** idx 종가 대비 idx+horizon 종가 수익률. 범위를 벗어나거나 종가가 0이면 null(데이터 부족). */
    static Double forwardReturn(List<Double> closes, int idx, int horizon) {
        int exit = idx + horizon;
        if (idx < 0 || exit >= closes.size()) return null;
        double entry = closes.get(idx);
        if (entry <= 0) return null;
        return closes.get(exit) / entry - 1.0;
    }

    /**
     * 표본 접기의 핵심 — 앵커 이벤트별로 버킷 중앙값 하나로 접고, 그 이벤트 중앙값들만
     * 호라이즌·버킷별로 모은다. 결과 HorizonStat.events는 종목 수가 아니라 <b>이벤트 수</b>.
     * 저·고·KOSPI 세 칸의 n을 같게 유지하려고, 한 이벤트가 어떤 호라이즌에 기여하려면
     * 저·고 버킷 커버리지와 KOSPI 전방 데이터가 <b>동시에</b> 있어야 한다.
     *
     * <p>버킷을 가르는 변동성은 {@code scope.window()}가 정한다(PRE/DURING). 창별로 따로 호출해
     * 두 결과를 나란히 비교한다.
     */
    static List<HorizonStat> aggregate(List<Integer> anchorIndices, CrashVolScope scope) {
        EventMedians medians = EventMedians.init(scope.horizons());
        for (int anchorIdx : anchorIndices) {
            accumulateAnchor(anchorIdx, scope, medians);
        }

        List<HorizonStat> out = new ArrayList<>();
        for (int h : scope.horizons()) {
            out.add(new HorizonStat(h, Quantiles.of(medians.low().get(h)),
                    Quantiles.of(medians.mid().get(h)), Quantiles.of(medians.high().get(h)),
                    Quantiles.of(medians.kospi().get(h)), medians.low().get(h).size()));
        }
        return out;
    }

    /** 앵커 1건 — 버킷을 가르고, 호라이즌별로 이벤트 중앙값 하나씩을 적립한다 */
    private static void accumulateAnchor(int anchorIdx, CrashVolScope scope, EventMedians medians) {
        LocalDate anchorDate = scope.kospiDates().get(anchorIdx);
        LowVolCrashBacktester.VolWindow window = scope.window();
        int minCoverage = scope.minCoverage();

        // 앵커일 기준 종목별 변동성(창 정의는 window) + 앵커일 종목 인덱스
        Map<String, Double> volBySymbol = new LinkedHashMap<>();
        Map<String, Integer> idxAtAnchor = new HashMap<>();
        for (Map.Entry<String, StockSeries> e : scope.universe().entrySet()) {
            StockSeries s = e.getValue();
            Integer si = s.dateIndex().get(anchorDate);
            if (si == null || si < window.minIndex()) continue; // 창만큼 이력 없으면 제외
            double vol = logReturnStdev(window.slice(s.closes(), si));
            volBySymbol.put(e.getKey(), vol);
            idxAtAnchor.put(e.getKey(), si);
        }

        Buckets b = terciles(volBySymbol);
        if (b.low().size() < minCoverage || b.high().size() < minCoverage) {
            log.info("[CrashVol] {} 앵커 스킵[{}] — 커버리지 부족 (저 {}·고 {} < {})",
                    anchorDate, window.code(), b.low().size(), b.high().size(), minCoverage);
            return;
        }
        log.info("[CrashVol] {} 앵커[{}] — 종목 {} (저 {}·중 {}·고 {})", anchorDate,
                window.code(), volBySymbol.size(), b.low().size(), b.mid().size(), b.high().size());

        for (int h : scope.horizons()) {
            List<Double> lowR = bucketForward(b.low(), scope.universe(), idxAtAnchor, h);
            List<Double> highR = bucketForward(b.high(), scope.universe(), idxAtAnchor, h);
            List<Double> midR = bucketForward(b.mid(), scope.universe(), idxAtAnchor, h);
            Double kR = forwardReturn(scope.kospiCloses(), anchorIdx, h);
            if (lowR.size() < minCoverage || highR.size() < minCoverage || kR == null) continue;
            medians.low().get(h).add(Quantiles.of(lowR).median());
            medians.high().get(h).add(Quantiles.of(highR).median());
            medians.kospi().get(h).add(kR);
            if (midR.size() >= minCoverage) medians.mid().get(h).add(Quantiles.of(midR).median());
        }
    }

    private static List<Double> bucketForward(List<String> bucket, Map<String, StockSeries> universe,
                                              Map<String, Integer> idxAtAnchor, int h) {
        List<Double> rets = new ArrayList<>();
        for (String sym : bucket) {
            Double fr = forwardReturn(universe.get(sym).closes(), idxAtAnchor.get(sym), h);
            if (fr != null) rets.add(fr);
        }
        return rets;
    }

    /** 호라이즌별 이벤트 중앙값 적립통 (저/중/고/KOSPI) */
    private record EventMedians(Map<Integer, List<Double>> low, Map<Integer, List<Double>> mid,
                                Map<Integer, List<Double>> high, Map<Integer, List<Double>> kospi) {

        static EventMedians init(List<Integer> horizons) {
            return new EventMedians(initHorizonLists(horizons), initHorizonLists(horizons),
                    initHorizonLists(horizons), initHorizonLists(horizons));
        }

        private static Map<Integer, List<Double>> initHorizonLists(List<Integer> horizons) {
            Map<Integer, List<Double>> m = new LinkedHashMap<>();
            for (int h : horizons) m.put(h, new ArrayList<>());
            return m;
        }
    }
}
