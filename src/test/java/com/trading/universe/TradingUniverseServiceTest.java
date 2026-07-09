package com.trading.universe;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 매매 유니버스 관리 — 인메모리 스텁 저장소로 시드·상한·중복 규칙 검증.
 */
@DisplayName("TradingUniverseService — 시드·편입·제외·상한")
class TradingUniverseServiceTest {

    private final List<TradingUniverseItem> store = new ArrayList<>();
    private TradingUniverseRepository repository;
    private TradingUniverseService sut;

    @BeforeEach
    void setUp() {
        store.clear();
        repository = mock(TradingUniverseRepository.class);
        when(repository.count()).thenAnswer(inv -> (long) store.size());
        when(repository.findAll()).thenAnswer(inv -> List.copyOf(store));
        when(repository.existsByStockCode(anyString())).thenAnswer(inv ->
                store.stream().anyMatch(i -> i.getStockCode().equals(inv.getArgument(0))));
        when(repository.save(any(TradingUniverseItem.class))).thenAnswer(inv -> {
            TradingUniverseItem item = inv.getArgument(0);
            store.add(item);
            return item;
        });
        sut = new TradingUniverseService(repository);
    }

    @Test
    @DisplayName("빈 테이블 → 삼성전자(005930) 시드 (기존 1종목 동작 보존)")
    void seeds_samsung_when_empty() {
        sut.seedIfEmpty();

        assertThat(sut.getActiveCodes()).containsExactly("005930");
    }

    @Test
    @DisplayName("이미 종목 존재 → 시드 안 함")
    void does_not_seed_when_populated() {
        store.add(TradingUniverseItem.of("000660", "SK하이닉스"));

        sut.seedIfEmpty();

        assertThat(sut.getActiveCodes()).containsExactly("000660");
    }

    @Test
    @DisplayName("정상 편입 → 저장 + 코드 목록 반영")
    void adds_valid_stock() {
        sut.add("000660", "SK하이닉스");

        assertThat(sut.getActiveCodes()).containsExactly("000660");
    }

    @Test
    @DisplayName("종목코드 형식 오류(6자리 숫자 아님) → 거부")
    void rejects_invalid_code_format() {
        assertThatThrownBy(() -> sut.add("ABC123", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sut.add("12345", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("중복 편입 → 거부")
    void rejects_duplicate() {
        store.add(TradingUniverseItem.of("005930", "삼성전자"));

        assertThatThrownBy(() -> sut.add("005930", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미");
    }

    @Test
    @DisplayName("상한 20종목 도달 → 편입 거부 (방법론 §2.5)")
    void rejects_beyond_cap() {
        for (int i = 0; i < TradingUniverseService.MAX_UNIVERSE_SIZE; i++) {
            store.add(TradingUniverseItem.of(String.format("%06d", i), null));
        }

        assertThatThrownBy(() -> sut.add("999999", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("상한");
    }

    @Test
    @DisplayName("없는 종목 제외 시도 → 거부")
    void rejects_removing_unknown() {
        assertThatThrownBy(() -> sut.remove("005930"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
