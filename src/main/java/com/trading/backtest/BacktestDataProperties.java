package com.trading.backtest;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

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
     * B-4 이벤트 통계 전용 추가 표본 (targetSymbols와 합집합).
     * B-3 전략 백테스트 유니버스에는 포함되지 않는다 — 통계 표본만 넓히는 용도.
     * 기본값: 대형주는 공시 반응이 약하다는 1차 결과에 따라 KOSDAQ 유동성 상위 후보.
     */
    private List<String> eventSymbols = List.of(
            "247540", // 에코프로비엠
            "086520", // 에코프로
            "196170", // 알테오젠
            "028300", // HLB
            "277810", // 레인보우로보틱스
            "263750", // 펄어비스
            "293490", // 카카오게임즈
            "112040"  // 위메이드
    );

    /** 적재/재생 기간 (년) — 설계 문서 §2.2 최소 3년 */
    private int years = 3;

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
     * (KIS/DART 응답에 시장 구분이 없어 v1은 설정으로 관리 — eventSymbols 기본값과 일치)
     */
    private List<String> kosdaqSymbols = List.of(
            "247540", "086520", "196170", "028300", "277810", "263750", "293490", "112040");

    /** 시뮬 계좌 초기 현금 (원) */
    private double initialCash = 10_000_000;

    /** smoke = 전기간 1런 (배관 검증), full = B-3 전체 (K 민감도 + WF + 필터 A/B) */
    private String mode = "full";

    public List<String> getSymbols() { return symbols; }
    public void setSymbols(List<String> symbols) { this.symbols = symbols; }

    public List<String> getEventSymbols() { return eventSymbols; }
    public void setEventSymbols(List<String> eventSymbols) { this.eventSymbols = eventSymbols; }

    public int getYears() { return years; }
    public void setYears(int years) { this.years = years; }

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
}
