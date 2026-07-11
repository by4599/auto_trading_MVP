package com.trading.strategy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalTime;

/**
 * 진입/청산 필터 4종 스위치 (설계 문서 §3.3 — 스위치는 만들되, 켜는 건 이긴 다음).
 *
 * 전부 기본 OFF. B-3 A/B 검증에서 순정 대비 검증 구간 PF·MDD 양쪽 우위일 때만 ON.
 * 진입 시간창·거래량 필터는 일봉 백테스트로 선견 편향 없는 검증이 불가능하므로
 * (돌파 "시각"이 필요) 분봉 축적 후 검증 전까지 켜지 않는다.
 */
@ConfigurationProperties(prefix = "trading.filters")
public class FilterProperties {

    private final EntryWindow entryWindow = new EntryWindow();
    private final VolumeConfirm volumeConfirm = new VolumeConfirm();
    private final TrailingStop trailingStop = new TrailingStop();
    private final IndexRegime indexRegime = new IndexRegime();

    public EntryWindow getEntryWindow()     { return entryWindow; }
    public VolumeConfirm getVolumeConfirm() { return volumeConfirm; }
    public TrailingStop getTrailingStop()   { return trailingStop; }
    public IndexRegime getIndexRegime()     { return indexRegime; }

    /** 장초반 휩소성 가짜 돌파 회피 — notBefore 이전 신규 진입 금지 */
    public static class EntryWindow {
        private boolean enabled = false;
        private LocalTime notBefore = LocalTime.of(9, 15);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public LocalTime getNotBefore() { return notBefore; }
        public void setNotBefore(LocalTime notBefore) { this.notBefore = notBefore; }
    }

    /** 거래량 동반 돌파만 신뢰 — 당일 누적 거래량 ≥ 전일 총량 × ratio */
    public static class VolumeConfirm {
        private boolean enabled = false;
        private double ratio = 1.5;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getRatio() { return ratio; }
        public void setRatio(double ratio) { this.ratio = ratio; }
    }

    /** +armProfit 도달 후 고점 대비 trail 하락 시 청산 (타임컷 보완) */
    public static class TrailingStop {
        private boolean enabled = false;
        private double armProfitPct = 0.03;
        private double trailPct = 0.02;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getArmProfitPct() { return armProfitPct; }
        public void setArmProfitPct(double armProfitPct) { this.armProfitPct = armProfitPct; }
        public double getTrailPct() { return trailPct; }
        public void setTrailPct(double trailPct) { this.trailPct = trailPct; }
    }

    /** 지수 약세일의 개별 돌파 불신 — KOSPI 갭다운 시 신규 진입 금지 (일봉 변형) */
    public static class IndexRegime {
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
