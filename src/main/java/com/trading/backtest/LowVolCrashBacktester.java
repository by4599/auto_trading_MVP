package com.trading.backtest;

import com.trading.backtest.EventStatsBacktester.Quantiles;
import com.trading.market.CandleHistory;
import com.trading.market.CandleHistoryRepository;
import com.trading.market.Timeframe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 약세장 방어 측정 실험 (BACKLOG "[2026-07-24] 약세장 방어" 탐색 — 전략 채택 아님, 리포트 전용).
 *
 * 질문: 지수(KOSPI)가 급락한 시점에, 그 직전까지 저변동성(덜 출렁인) 종목이 그 뒤 더
 * 나았는가? 그게 "급등"인가 "덜 다치고 안정 회복"인가?
 *
 * 표본 부풀림 차단(SpilloverStatsBacktester의 정본 사상 그대로):
 *   한 급락 이벤트(날짜) 안의 여러 종목 수익률은 서로 강하게 상관되므로 독립 표본이
 *   아니다 → 버킷(저/중/고)별로 <b>이벤트당 횡단면 중앙값 하나로 접고</b>, 그 이벤트
 *   중앙값들만 호라이즌·버킷별로 모아 Quantiles로 집계한다. 표본 크기 n은 절대
 *   (종목 수 × 이벤트 수)가 아니라 <b>이벤트 수</b>다.
 *
 * 변동성 창은 <b>두 정의를 모두</b> 재서 나란히 본다(어느 쪽이 진짜인지 데이터가 답하게):
 *   PRE    = 급락 구간을 완전히 배제한 앵커 -30~-10거래일 → "급락 <b>전까지</b> 조용했나"
 *   DURING = 급락 국면을 포함한 앵커 -20~0거래일 → "이번 급락에서 <b>덜 맞았나</b>"
 * 둘은 다른 질문이며, DURING만 보면 되돌림(덜 맞은 종목의 반등)을 저변동성 효과로 오독한다.
 *
 * 사후편향 방지: 두 창 모두 앵커일 종가까지의 과거만 쓰고(정보집합 = 진입 시점),
 * 전방 수익률만 미래를 본다. 급락 앵커는 <b>전방창이 서로 겹치지 않도록</b> 최대 호라이즌
 * (D+{@value #MAX_HORIZON}) 이상의 쿨다운으로 솎는다.
 *
 * 거버넌스 기준선 yml은 쓰지 않는다 — exit-lab처럼 측정까지다.
 */
@Component
@Profile("backtest")
public class LowVolCrashBacktester {

    private static final Logger log = LoggerFactory.getLogger(LowVolCrashBacktester.class);

    /** 급락 앵커 기본 임계치 — 트레일링 10거래일 지수 수익률 ≤ -7% */
    static final double ANCHOR_THRESHOLD = -0.07;

    /** 트레일링 지수 수익률 창 (거래일) */
    static final int TRAILING_DAYS = 10;

    /** 전방 수익률 호라이즌 (거래일) */
    static final List<Integer> HORIZONS = List.of(5, 10, 20, 60);

    /** 최대 호라이즌 (거래일) — 쿨다운 하한의 근거 */
    static final int MAX_HORIZON = 60;

    /**
     * 앵커 쿨다운 (거래일) — 기본 60. <b>최대 호라이즌 이상</b>이어야 앵커들의 전방창이
     * 서로 겹치지 않아 이벤트 중앙값들이 독립 표본이 된다. 20이면 D+60 창이 최대 40일
     * 겹쳐 n이 유효표본을 과대표시했다. 이 창(2023~2026)에서 이벤트가 줄어드는 건 감수하고,
     * 줄면 준 대로 리포트에 찍는다(낮은 검정력을 숨기지 않는다).
     */
    static final int COOLDOWN_DAYS = 60;

    /** 변동성 측정 창 길이 (거래일 수) — 20개 로그수익률(=21종가) */
    static final int VOL_WINDOW = 20;

    /** 버킷당 최소 커버리지 — 미만이면 횡단면 중앙값이 개별 종목 노이즈다 (Spillover와 동일 사상) */
    static final int MIN_BUCKET_COVERAGE = 3;

    /**
     * 급락 <b>이전</b> 변동성 창 — 앵커 -30 ~ -10거래일. 트레일링 10거래일 급락 구간을
     * 완전히 배제한 20개 로그수익률이다. 사용자 질문("급락 전까지 덜 출렁이던 종목")의 정의.
     */
    static final VolWindow PRE_WINDOW = new VolWindow("PRE",
            "급락 이전 (앵커 -30~-10거래일 · 급락 구간 완전 배제)",
            VOL_WINDOW + TRAILING_DAYS, TRAILING_DAYS);

    /**
     * 급락 <b>국면 포함</b> 변동성 창 — 앵커 -20 ~ 0거래일. 뒤쪽 10일이 급락 구간과 겹치므로
     * 이것이 재는 건 "이번 급락에서 덜 맞은 정도"다. 선견편향은 아니다(정보집합이 앵커
     * 종가까지로 진입 시점과 일치) — 폐기하지 않고 PRE와 병기한다.
     */
    static final VolWindow DURING_WINDOW = new VolWindow("DURING",
            "급락 국면 포함 (앵커 -20~0거래일 · 이번 급락에서 덜 맞은 정도)",
            VOL_WINDOW, 0);

    /** 나란히 보고할 변동성 창 2종 */
    static final List<VolWindow> VOL_WINDOWS = List.of(PRE_WINDOW, DURING_WINDOW);

    /** 검정력 정직성 — 임계치 3종별 이벤트 개수를 리포트에 남긴다 (본 통계는 -7%) */
    static final List<Double> THRESHOLD_GRID = List.of(-0.05, -0.07, -0.10);

    private final CandleHistoryRepository candleHistoryRepository;
    private final BacktestDataProperties properties;

    public LowVolCrashBacktester(CandleHistoryRepository candleHistoryRepository,
                                 BacktestDataProperties properties) {
        this.candleHistoryRepository = candleHistoryRepository;
        this.properties = properties;
    }

    public CrashVolReport compute(List<String> universe, LocalDate from, LocalDate to) {
        List<CandleHistory> kospiCandles = loadDaily(properties.getKospiStorageCode(), from, to);
        if (kospiCandles.isEmpty()) {
            log.warn("[CrashVol] KOSPI 일봉 없음 — 급락 앵커를 잡을 수 없어 통계를 건너뜀 "
                    + "(includeKospi=true·백필 확인)");
            return new CrashVolReport(from, to, universe.size(), ANCHOR_THRESHOLD, COOLDOWN_DAYS,
                    MIN_BUCKET_COVERAGE, Map.of(), 0, List.of());
        }

        List<Double> kospiCloses = kospiCandles.stream().map(CandleHistory::getClose).toList();
        List<LocalDate> kospiDates = kospiCandles.stream().map(CandleHistory::getCandleDate).toList();

        // 재현성: 전방 수익률 데이터 확보를 위해 to 이후 캔들까지 로드하지만, 앵커 탐지는
        // 선언 기간(to)까지로 자른다. 앞쪽 prefix라 인덱스는 전체 시계열과 그대로 호환된다.
        List<Double> anchorScope = kospiCloses.subList(0, anchorScopeSize(kospiDates, to));

        Map<Double, Integer> thresholdCounts = new LinkedHashMap<>();
        for (double t : THRESHOLD_GRID) {
            thresholdCounts.put(t,
                    detectAnchorIndices(anchorScope, t, TRAILING_DAYS, COOLDOWN_DAYS).size());
        }
        List<Integer> anchors =
                detectAnchorIndices(anchorScope, ANCHOR_THRESHOLD, TRAILING_DAYS, COOLDOWN_DAYS);

        logBanner(from, to, universe.size(), thresholdCounts, anchors.size());

        Map<String, StockSeries> series = new LinkedHashMap<>();
        for (String sym : universe) {
            series.put(sym, StockSeries.of(loadDaily(sym, from, to)));
        }

        List<WindowStats> windows = new ArrayList<>();
        for (VolWindow w : VOL_WINDOWS) {
            windows.add(new WindowStats(w, aggregate(anchors, kospiCloses, kospiDates, series,
                    w, MIN_BUCKET_COVERAGE, HORIZONS)));
        }

        return new CrashVolReport(from, to, universe.size(), ANCHOR_THRESHOLD, COOLDOWN_DAYS,
                MIN_BUCKET_COVERAGE, thresholdCounts, anchors.size(), windows);
    }

    // ── 순수 계산 헬퍼 (목 없이 단위 검증 가능) ─────────────────────────────────

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
     * <p>버킷을 가르는 변동성은 {@code window}가 정한다(PRE/DURING). 창별로 따로 호출해
     * 두 결과를 나란히 비교한다.
     */
    static List<HorizonStat> aggregate(List<Integer> anchorIndices,
                                       List<Double> kospiCloses, List<LocalDate> kospiDates,
                                       Map<String, StockSeries> universe,
                                       VolWindow window, int minCoverage, List<Integer> horizons) {
        Map<Integer, List<Double>> lowMed = initHorizonLists(horizons);
        Map<Integer, List<Double>> midMed = initHorizonLists(horizons);
        Map<Integer, List<Double>> highMed = initHorizonLists(horizons);
        Map<Integer, List<Double>> kospiRet = initHorizonLists(horizons);

        for (int anchorIdx : anchorIndices) {
            LocalDate anchorDate = kospiDates.get(anchorIdx);

            // 앵커일 기준 종목별 변동성(창 정의는 window) + 앵커일 종목 인덱스
            Map<String, Double> volBySymbol = new LinkedHashMap<>();
            Map<String, Integer> idxAtAnchor = new HashMap<>();
            for (Map.Entry<String, StockSeries> e : universe.entrySet()) {
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
                continue;
            }
            log.info("[CrashVol] {} 앵커[{}] — 종목 {} (저 {}·중 {}·고 {})", anchorDate,
                    window.code(), volBySymbol.size(), b.low().size(), b.mid().size(), b.high().size());

            for (int h : horizons) {
                List<Double> lowR = bucketForward(b.low(), universe, idxAtAnchor, h);
                List<Double> highR = bucketForward(b.high(), universe, idxAtAnchor, h);
                List<Double> midR = bucketForward(b.mid(), universe, idxAtAnchor, h);
                Double kR = forwardReturn(kospiCloses, anchorIdx, h);
                if (lowR.size() < minCoverage || highR.size() < minCoverage || kR == null) continue;
                lowMed.get(h).add(Quantiles.of(lowR).median());
                highMed.get(h).add(Quantiles.of(highR).median());
                kospiRet.get(h).add(kR);
                if (midR.size() >= minCoverage) midMed.get(h).add(Quantiles.of(midR).median());
            }
        }

        List<HorizonStat> out = new ArrayList<>();
        for (int h : horizons) {
            out.add(new HorizonStat(h, Quantiles.of(lowMed.get(h)), Quantiles.of(midMed.get(h)),
                    Quantiles.of(highMed.get(h)), Quantiles.of(kospiRet.get(h)), lowMed.get(h).size()));
        }
        return out;
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

    private static Map<Integer, List<Double>> initHorizonLists(List<Integer> horizons) {
        Map<Integer, List<Double>> m = new LinkedHashMap<>();
        for (int h : horizons) m.put(h, new ArrayList<>());
        return m;
    }

    /**
     * 일봉 로드 — {@code to.plusDays(120)}의 여유분은 <b>전방 수익률(D+60) 데이터 확보 전용</b>이다.
     * 앵커 탐지는 {@link #anchorScopeSize}로 {@code to}까지 잘라 쓴다(재현성).
     */
    private List<CandleHistory> loadDaily(String symbol, LocalDate from, LocalDate to) {
        return candleHistoryRepository
                .findByStockCodeAndTimeframeAndCandleDateBetweenOrderByCandleDateAscCandleTimeAsc(
                        symbol, Timeframe.DAILY, from, to.plusDays(120)); // D+60 거래일 여유
    }

    private void logBanner(LocalDate from, LocalDate to, int universeSize,
                           Map<Double, Integer> thresholdCounts, int anchorCount) {
        log.info("[CrashVol] ══ 약세장 방어 측정 (BACKLOG 2026-07-24 탐색 — 리포트 전용) ══");
        log.info("[CrashVol] 기간: {} ~ {} · 유니버스 {}종목 (앵커 탐지는 {}까지로 클램프)",
                from, to, universeSize, to);
        log.info("[CrashVol] 급락 정의: 트레일링 {}거래일 지수수익률 ≤ 임계 · 쿨다운 {}거래일"
                        + "(≥최대 호라이즌 D+{} — 전방창 중첩 제거)",
                TRAILING_DAYS, COOLDOWN_DAYS, MAX_HORIZON);
        for (VolWindow w : VOL_WINDOWS) {
            log.info("[CrashVol] 변동성 창 {} — {}", w.code(), w.label());
        }
        thresholdCounts.forEach((t, c) ->
                log.info("[CrashVol] 임계 {}% → 앵커 {}건", String.format("%.0f", t * 100), c));
        log.info("[CrashVol] 본 통계 임계 {}% → 앵커 {}건으로 집계 시작",
                String.format("%.0f", ANCHOR_THRESHOLD * 100), anchorCount);
    }

    // ── 타입 ──────────────────────────────────────────────────────────────────

    /**
     * 변동성 측정 창 — 앵커 인덱스 si 기준 종가 구간 {@code [si-startBack, si-endBack]}(양끝 포함).
     * 로그수익률 개수는 {@code startBack-endBack}개다. {@link #PRE_WINDOW}/{@link #DURING_WINDOW}
     * 두 정의를 모두 재서 나란히 보고한다.
     */
    public record VolWindow(String code, String label, int startBack, int endBack) {
        /** 이 창을 재려면 종목에 필요한 최소 앵커 인덱스 — 미달 종목은 그 집계에서만 제외 */
        public int minIndex() {
            return startBack;
        }

        /** 앵커 인덱스 si에서의 종가 구간 (양끝 포함) */
        public List<Double> slice(List<Double> closes, int si) {
            return closes.subList(si - startBack, si - endBack + 1);
        }
    }

    /** 변동성 3등분 버킷 (종목 코드 목록) */
    public record Buckets(List<String> low, List<String> mid, List<String> high) {}

    /** 한 종목의 종가 시계열 + 날짜→인덱스 맵 (aggregate가 목 없이 받도록 in-memory 표현) */
    public record StockSeries(List<Double> closes, Map<LocalDate, Integer> dateIndex) {
        public static StockSeries of(List<CandleHistory> candles) {
            List<Double> closes = new ArrayList<>(candles.size());
            Map<LocalDate, Integer> idx = new HashMap<>();
            for (int i = 0; i < candles.size(); i++) {
                closes.add(candles.get(i).getClose());
                idx.put(candles.get(i).getCandleDate(), i);
            }
            return new StockSeries(closes, idx);
        }
    }

    /** 호라이즌별 저/중/고 버킷 전방수익률 이벤트 집계 + KOSPI 벤치마크. events = 이벤트 수. */
    public record HorizonStat(int horizon, Quantiles lowVol, Quantiles midVol, Quantiles highVol,
                              Quantiles kospi, int events) {
        /** 저−고 중앙값 차 (초과수익 판독) */
        public double lowMinusHigh() {
            return lowVol.median() - highVol.median();
        }
    }

    /** 변동성 창 하나로 버킷팅한 집계 결과 (창 정의 + 호라이즌별 통계) */
    public record WindowStats(VolWindow window, List<HorizonStat> horizons) {}

    /** 리포트 입력 전체 — 거버넌스 기준선 미기록(측정 전용). windows = PRE/DURING 두 벌 */
    public record CrashVolReport(LocalDate from, LocalDate to, int universeSize,
                                 double threshold, int cooldownDays, int minCoverage,
                                 Map<Double, Integer> thresholdCounts, int anchorCount,
                                 List<WindowStats> windows) {}
}
