package com.trading.backtest;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 백테스트 설정 (B-1/B-2).
 *
 * symbols는 유니버스와 별개의 "백테스트 전용" 표본 확대 목록 —
 * trading_universe(수동 게이트 G1)에는 삽입하지 않는다.
 */
@ConfigurationProperties(prefix = "backtest")
public class BacktestDataProperties {

    /** 백테스트 전용 추가 종목 (유니버스 활성 종목과 합집합으로 사용) */
    private List<String> symbols = List.of(
            "005930", // 삼성전자
            "000660", // SK하이닉스
            "373220", // LG에너지솔루션
            "005380", // 현대차
            "035420", // NAVER
            "068270"  // 셀트리온
    );

    /**
     * B-4 이벤트 통계 전용 추가 표본 — 테마(밸류체인) → 종목 매핑 (targetSymbols와 합집합).
     * B-3 전략 백테스트 유니버스에는 포함되지 않는다 — 통계 표본만 넓히는 용도.
     * 테마 키는 "앵커 대형주 호재 → 동일 테마 관련주 파급(spillover)" 통계의 그룹 축으로
     * 쓸 예정이며, 평면 목록이 필요한 기존 소비처는 getEventSymbols() 평면화를 그대로 쓴다.
     * 원본은 application.yml의 backtest.event-themes.
     */
    private Map<String, List<String>> eventThemes = new LinkedHashMap<>();

    /** 적재/재생 기간 (년) — 설계 문서 §2.2 최소 3년 */
    private int years = 3;

    /**
     * candle_history 소급 저장 하한 (선택). null이면 기존 계산(now - years - 워밍업 260일) 그대로.
     *
     * <p><b>저장 깊이만</b> 바꾼다 — 판정 창(전역 from/to·years·candidate-from/to)과 무관하다.
     * 기존 모드(full/smoke/ma-breakout/scalping/events/exit-lab)는 여전히 now-years로 재생하고
     * {@code BacktestMarketDataService}가 from-260일까지만 조회하므로, 이 값을 과거로 당겨
     * 더 오래된 캔들을 쌓아도 그 모드들의 결과는 바뀌지 않는다.
     */
    private LocalDate backfillFrom = null;

    /** 지수 레짐 필터용 KOSPI 지수 적재 여부 */
    private boolean includeKospi = true;

    /** KIS 업종 코드 — KOSPI = 0001 */
    private String kospiCode = "0001";

    /** candle_history에 지수를 저장할 때 쓰는 식별자 */
    private String kospiStorageCode = "KOSPI";

    /** KIS 업종 코드 — KOSDAQ 종합 = 1001 */
    private String kosdaqCode = "1001";

    private String kosdaqStorageCode = "KOSDAQ";

    /**
     * KOSDAQ 소속 종목 — B-4 벤치마크를 KOSDAQ 지수로 분리하기 위한 명시 목록.
     * (KIS/DART 응답에 시장 구분이 없어 v1은 설정으로 관리 — 원본은 application.yml)
     */
    private List<String> kosdaqSymbols = List.of(
            "247540", "086520", "196170", "028300", "277810", "263750", "293490", "112040");

    /** 시뮬 계좌 초기 현금 (원) */
    private double initialCash = 10_000_000;

    /** smoke = 전기간 1런 (배관 검증), full = B-3 전체 (K 민감도 + WF + 필터 A/B) */
    private String mode = "full";

    /** 유동성 필터 임계값 (원/일) — 미달 종목은 VB 유니버스 재검증에서 제외 (방법론 §2.2) */
    private double minDailyTradingValue = 5_000_000_000d;

    /**
     * §14.1 검증 후보(MA+P3+RR1) 전용 고정 유니버스 — 재현성을 위해 저장소에 못 박는다.
     * 전역 symbols(6종목)와 별개이며 cost-lab·risk-lab만 사용한다.
     * (출처: logs/backtest/REPORT-RISKLAB-MA-P3-20260722-2200.md 헤더 = §14.1 기준선 표본)
     */
    private List<String> candidateSymbols = List.of(
            "005930", "000660", "373220", "005380", "035420", "068270", "036930", "240810",
            "058470", "319660", "039030", "222800", "095610", "440110", "009150", "042700",
            "403870", "084370", "005290", "357780", "089030", "074600", "067310", "348210",
            "281820", "247540", "086520", "006400", "196170", "028300", "207940", "950160",
            "298380", "000250", "141080", "263750", "293490", "112040", "277810", "402340",
            "105560", "032830", "028260", "000270", "055550", "329180", "012450", "034020",
            "012330", "034730", "086790", "066570", "000810", "267260");

    /** §14.1 기준선 기간 시작 (고정 판정 창의 시작일) */
    private LocalDate candidateFrom = LocalDate.parse("2023-07-22");

    /**
     * §14.1 기준선을 재현하는 고정 판정 창의 끝. 새 데이터로 재검증하려면 이 값을 의도적으로
     * 바꾸거나 --backtest.candidate-from/to로 덮어쓴다(그게 '기준선을 새로 찍겠다'는 명시 행위다).
     */
    private LocalDate candidateTo = LocalDate.parse("2026-07-21");

    /**
     * crash-vol 전용 측정 창 시작 — 2020-01(코로나 폭락)·2022(금리 쇼크) 진짜 하락장을 포함한다.
     * candidate-from(§14.1 판정 창)과 <b>독립</b>이다: cost-lab·risk-lab은 재현 기준선이라
     * 창을 못 박아 두고, crash-vol만 소급 확장 데이터를 쓴다. 재현성을 위해 부동 날짜가 아닌
     * 고정값으로 둔다.
     */
    private LocalDate crashVolFrom = LocalDate.parse("2020-01-01");

    /** crash-vol 전용 측정 창 끝 — candidate-to와 같은 날이지만 별개 설정이다(우연한 결합 금지) */
    private LocalDate crashVolTo = LocalDate.parse("2026-07-21");

    /**
     * regime-lab 전용 <b>약세장 포함</b> 판정 창 시작 — §14.3 스트레스 실행과 같은 6.5년 창
     * (2020 코로나 · 2022 금리 쇼크 포함, Walk-Forward 24창). candidate-from(§14.1 재현 창)과
     * <b>독립</b>이다: cost-lab·risk-lab은 기준선 재현이라 창을 못 박아 두고, regime-lab만
     * 이 창을 쓴다. 재현성을 위해 부동 날짜가 아닌 고정값으로 둔다.
     */
    private LocalDate stressFrom = LocalDate.parse("2020-01-01");

    /** regime-lab 전용 판정 창 끝 — candidate-to와 같은 날이지만 별개 설정이다(우연한 결합 금지) */
    private LocalDate stressTo = LocalDate.parse("2026-07-21");

    /**
     * 거버넌스 기준선 yml 기록 여부 — 기본 false. 대조·점검용 lab 실행이 기준선을 조용히
     * 덮어쓰던 사고(2026-07-23) 차단. 기준선을 새로 찍으려면 --backtest.write-baseline=true를 명시한다.
     */
    private boolean writeBaseline = false;

    public List<String> getSymbols() { return symbols; }
    public void setSymbols(List<String> symbols) { this.symbols = symbols; }

    public Map<String, List<String>> getEventThemes() { return eventThemes; }
    public void setEventThemes(Map<String, List<String>> eventThemes) { this.eventThemes = new LinkedHashMap<>(eventThemes); }

    /** 테마 맵 평면화 (선언 순서 보존·중복 제거) — 백필·분봉 수집·B-4 등 기존 소비처 호환 */
    public List<String> getEventSymbols() {
        return eventThemes.values().stream().flatMap(List::stream).distinct().toList();
    }

    public int getYears() { return years; }
    public void setYears(int years) { this.years = years; }

    public LocalDate getBackfillFrom() { return backfillFrom; }
    public void setBackfillFrom(LocalDate backfillFrom) { this.backfillFrom = backfillFrom; }

    public boolean isIncludeKospi() { return includeKospi; }
    public void setIncludeKospi(boolean includeKospi) { this.includeKospi = includeKospi; }

    public String getKospiCode() { return kospiCode; }
    public void setKospiCode(String kospiCode) { this.kospiCode = kospiCode; }

    public String getKospiStorageCode() { return kospiStorageCode; }
    public void setKospiStorageCode(String kospiStorageCode) { this.kospiStorageCode = kospiStorageCode; }

    public String getKosdaqCode() { return kosdaqCode; }
    public void setKosdaqCode(String kosdaqCode) { this.kosdaqCode = kosdaqCode; }

    public String getKosdaqStorageCode() { return kosdaqStorageCode; }
    public void setKosdaqStorageCode(String kosdaqStorageCode) { this.kosdaqStorageCode = kosdaqStorageCode; }

    public List<String> getKosdaqSymbols() { return kosdaqSymbols; }
    public void setKosdaqSymbols(List<String> kosdaqSymbols) { this.kosdaqSymbols = kosdaqSymbols; }

    public double getInitialCash() { return initialCash; }
    public void setInitialCash(double initialCash) { this.initialCash = initialCash; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public double getMinDailyTradingValue() { return minDailyTradingValue; }
    public void setMinDailyTradingValue(double minDailyTradingValue) { this.minDailyTradingValue = minDailyTradingValue; }

    public List<String> getCandidateSymbols() { return candidateSymbols; }
    public void setCandidateSymbols(List<String> candidateSymbols) { this.candidateSymbols = candidateSymbols; }

    public LocalDate getCandidateFrom() { return candidateFrom; }
    public void setCandidateFrom(LocalDate candidateFrom) { this.candidateFrom = candidateFrom; }

    public LocalDate getCandidateTo() { return candidateTo; }
    public void setCandidateTo(LocalDate candidateTo) { this.candidateTo = candidateTo; }

    public LocalDate getCrashVolFrom() { return crashVolFrom; }
    public void setCrashVolFrom(LocalDate crashVolFrom) { this.crashVolFrom = crashVolFrom; }

    public LocalDate getCrashVolTo() { return crashVolTo; }
    public void setCrashVolTo(LocalDate crashVolTo) { this.crashVolTo = crashVolTo; }

    public LocalDate getStressFrom() { return stressFrom; }
    public void setStressFrom(LocalDate stressFrom) { this.stressFrom = stressFrom; }

    public LocalDate getStressTo() { return stressTo; }
    public void setStressTo(LocalDate stressTo) { this.stressTo = stressTo; }

    public boolean isWriteBaseline() { return writeBaseline; }
    public void setWriteBaseline(boolean writeBaseline) { this.writeBaseline = writeBaseline; }
}
