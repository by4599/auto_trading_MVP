package com.trading.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 63_quant 모드 라우팅. 창·유니버스는 55_quant와 같다(stress 6.5년 · 후보 54종목).
 * <ul>
 *   <li>{@code bear-inverse-fetch} — 114800 일봉만 수취(장 시간 거부). 다른 종목 백필은 {@code --backtest.offline=true}로 막고 실행한다</li>
 *   <li>{@code bear-inverse-anchor} — A동 D0′(MA120) 단독만, 오버레이 없음</li>
 *   <li>{@code bear-inverse-lab} — M 5개 × S/X, 오버레이 12칸 + 인버스 단독</li>
 *   <li>{@code bear-inverse-cost} — M=120 · {@code --backtest.bear-inverse.cost=<왕복>}</li>
 * </ul>
 * {@code --backtest.bear-inverse.synthetic=true}면 114800 대신 KOSPI 합성 −1배를 쓴다(기본 false).
 * {@code --backtest.bear-inverse.mas=120}처럼 주면 lab 모드의 M 목록을 그것으로 바꾼다(사후 비교용, 기본은 사전 등록 5개).
 */
@Component
@Profile("backtest")
public class BearInverseRouter {

    private static final Logger log = LoggerFactory.getLogger(BearInverseRouter.class);

    static final String LABEL = "A동 D0′(돈치안 · −15% 손절 · 0.25R · 1주 상한 1R×3 · 지수MA) · 하락장 인버스 보유";
    static final String SLUG = "DONCHIAN-F15-MIN1CAP3";
    static final List<Integer> LAB_MAS = List.of(120, 96, 144, 60, 200);
    static final LocalDate FETCH_FROM = LocalDate.of(2019, 4, 1);

    private final CandidateLabRouter candidateRouter;
    private final BearInverseLab lab;
    private final InverseData data;
    private final BacktestDataProperties properties;
    private final boolean synthetic;
    private final double cost;
    private final List<Integer> labMas;

    public BearInverseRouter(CandidateLabRouter candidateRouter, BearInverseLab lab, InverseData data,
                             BacktestDataProperties properties,
                             @Value("${backtest.bear-inverse.synthetic:false}") boolean synthetic,
                             @Value("${backtest.bear-inverse.cost:0}") double cost,
                             @Value("${backtest.bear-inverse.mas:}") List<Integer> mas) {
        this.candidateRouter = candidateRouter;
        this.lab = lab;
        this.data = data;
        this.properties = properties;
        this.synthetic = synthetic;
        this.cost = cost;
        this.labMas = mas == null || mas.isEmpty() ? LAB_MAS : List.copyOf(mas);
    }

    public boolean route(String mode) {
        if ("bear-inverse-fetch".equalsIgnoreCase(mode)) {
            int saved = data.fetchInverse(FETCH_FROM, properties.getStressTo());
            log.info("[BearInv] 수취 끝 — 신규 {}건", saved);
            return true;
        }
        if ("bear-inverse-anchor".equalsIgnoreCase(mode)) {
            run(mode, "ANCHOR", List.of(120), false, 0);
            return true;
        }
        if ("bear-inverse-lab".equalsIgnoreCase(mode)) {
            run(mode, (synthetic ? "LAB-SYNTH" : "LAB") + (labMas.equals(LAB_MAS) ? "" : "-M" + labMas), labMas, true, 0);
            return true;
        }
        if ("bear-inverse-cost".equalsIgnoreCase(mode)) {
            if (cost <= 0) throw new IllegalArgumentException("--backtest.bear-inverse.cost=<왕복 비율>이 필요하다 (예: 0.005)");
            run(mode, String.format("COST%.0fbp%s", cost * 10_000, synthetic ? "-SYNTH" : ""), List.of(120), true, cost);
            return true;
        }
        return false;
    }

    private void run(String mode, String tag, List<Integer> mas, boolean overlay, double roundTrip) {
        LabScope scope = candidateRouter.stressScope(mode, LABEL, SLUG);
        List<BearInverseLab.Section> sections = lab.run(scope, mas, overlay, roundTrip, synthetic);
        log.info("[BearInv] 리포트: {}", lab.report(scope, tag, sections, synthetic).toAbsolutePath());
    }
}
