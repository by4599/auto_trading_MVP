package com.trading.research;

import com.trading.research.DartApiClient.CorpInfo;
import com.trading.research.DartApiClient.DartDisclosure;
import com.trading.universe.TradingUniverseItem;
import com.trading.universe.TradingUniverseRepository;
import com.trading.universe.TradingUniverseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DART 공시 수집 (Phase 3a 수집부) — DartApiClient 인터페이스 목킹으로 로직만 검증.
 */
@DisplayName("DartDisclosureService — 공시 수집·중복 방지·유형 분류")
class DartDisclosureServiceTest {

    private DartApiClient dartApiClient;
    private DisclosureRepository disclosureRepository;
    private WatchlistRepository watchlistRepository;
    private TradingUniverseRepository universeRepository;
    private DartDisclosureService sut;

    @BeforeEach
    void setUp() {
        dartApiClient = mock(DartApiClient.class);
        disclosureRepository = mock(DisclosureRepository.class);
        watchlistRepository = mock(WatchlistRepository.class);
        universeRepository = mock(TradingUniverseRepository.class);
        sut = new DartDisclosureService(dartApiClient, disclosureRepository,
                watchlistRepository, new TradingUniverseService(universeRepository),
                new NewsSentimentAnalyzer());

        when(dartApiClient.isConfigured()).thenReturn(true);
        when(universeRepository.findAll()).thenReturn(List.of());
        when(watchlistRepository.findAll()).thenReturn(List.of());
    }

    private void givenUniverse(String... codes) {
        when(universeRepository.findAll()).thenReturn(
                java.util.Arrays.stream(codes).map(c -> TradingUniverseItem.of(c, c)).toList());
    }

    private static DartDisclosure disclosure(String receiptNo, String reportName) {
        return new DartDisclosure(receiptNo, reportName, "삼성전자", LocalDate.now());
    }

    @Test
    @DisplayName("인증키 미설정 → 수집 안 함")
    void skips_when_not_configured() {
        when(dartApiClient.isConfigured()).thenReturn(false);
        givenUniverse("005930");

        assertThat(sut.aggregate()).isEqualTo(0);
        verify(dartApiClient, never()).fetchCorpCodeMap();
    }

    @Test
    @DisplayName("유니버스 종목 공시 수집 → 저장")
    void collects_and_saves_disclosures() {
        givenUniverse("005930");
        when(dartApiClient.fetchCorpCodeMap())
                .thenReturn(Map.of("005930", new CorpInfo("00126380", "삼성전자")));
        when(dartApiClient.fetchRecentDisclosures(eq("00126380"), any(), any()))
                .thenReturn(List.of(disclosure("20260709000001", "단일판매ㆍ공급계약체결")));

        int saved = sut.aggregate();

        assertThat(saved).isEqualTo(1);
        ArgumentCaptor<DisclosureItem> captor = ArgumentCaptor.forClass(DisclosureItem.class);
        verify(disclosureRepository).save(captor.capture());
        assertThat(captor.getValue().getStockCode()).isEqualTo("005930");
        assertThat(captor.getValue().getSentiment()).isEqualTo("POSITIVE");
    }

    @Test
    @DisplayName("이미 저장된 접수번호 → 중복 저장 안 함")
    void deduplicates_by_receipt_no() {
        givenUniverse("005930");
        when(dartApiClient.fetchCorpCodeMap())
                .thenReturn(Map.of("005930", new CorpInfo("00126380", "삼성전자")));
        when(dartApiClient.fetchRecentDisclosures(anyString(), any(), any()))
                .thenReturn(List.of(disclosure("20260709000001", "주요사항보고서")));
        when(disclosureRepository.existsByReceiptNo("20260709000001")).thenReturn(true);

        assertThat(sut.aggregate()).isEqualTo(0);
        verify(disclosureRepository, never()).save(any());
    }

    @Test
    @DisplayName("corp_code 매핑 없는 종목 → 건너뛰고 계속")
    void skips_unmapped_stock() {
        givenUniverse("005930", "999999");
        when(dartApiClient.fetchCorpCodeMap())
                .thenReturn(Map.of("005930", new CorpInfo("00126380", "삼성전자")));
        when(dartApiClient.fetchRecentDisclosures(anyString(), any(), any())).thenReturn(List.of());

        sut.aggregate();

        verify(dartApiClient, times(1)).fetchRecentDisclosures(anyString(), any(), any());
    }

    @Test
    @DisplayName("워치리스트 종목도 수집 대상에 포함 (유니버스와 합집합)")
    void includes_watchlist_targets() {
        givenUniverse("005930");
        when(watchlistRepository.findAll())
                .thenReturn(List.of(WatchlistItem.of("000660", "SK하이닉스", null)));
        when(dartApiClient.fetchCorpCodeMap()).thenReturn(Map.of(
                "005930", new CorpInfo("00126380", "삼성전자"),
                "000660", new CorpInfo("00164779", "SK하이닉스")));
        when(dartApiClient.fetchRecentDisclosures(anyString(), any(), any())).thenReturn(List.of());

        sut.aggregate();

        verify(dartApiClient).fetchRecentDisclosures(eq("00126380"), any(), any());
        verify(dartApiClient).fetchRecentDisclosures(eq("00164779"), any(), any());
    }

    @Test
    @DisplayName("corp 매핑은 캐시 — 두 번째 수집에서 재다운로드 안 함")
    void caches_corp_map() {
        givenUniverse("005930");
        when(dartApiClient.fetchCorpCodeMap())
                .thenReturn(Map.of("005930", new CorpInfo("00126380", "삼성전자")));
        when(dartApiClient.fetchRecentDisclosures(anyString(), any(), any())).thenReturn(List.of());

        sut.aggregate();
        sut.aggregate();

        verify(dartApiClient, times(1)).fetchCorpCodeMap();
        verify(dartApiClient, atLeastOnce()).fetchRecentDisclosures(anyString(), any(), any());
    }

    // ── 공시 유형 분류 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("공시 유형 오버라이드: 공급계약=호재, 유상증자=악재, 소송=악재")
    void classifies_report_types() {
        assertThat(sut.classify("단일판매ㆍ공급계약체결")).isEqualTo("POSITIVE");
        assertThat(sut.classify("유상증자결정")).isEqualTo("NEGATIVE");
        assertThat(sut.classify("소송등의제기")).isEqualTo("NEGATIVE");
        assertThat(sut.classify("무상증자결정")).isEqualTo("POSITIVE");
        assertThat(sut.classify("주요사항보고서")).isEqualTo("NEUTRAL");
    }
}
