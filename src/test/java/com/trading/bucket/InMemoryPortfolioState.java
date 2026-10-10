package com.trading.bucket;

import com.trading.position.PortfolioState;
import com.trading.position.PortfolioStateRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 테스트용 portfolio_state — 맵에 저장하고 다시 읽는 리포지토리 목(인터페이스 목, Java 25 Mockito 제약 준수).
 *
 * <p>저장한 값이 다음 조회에 그대로 보여야 "재시작 후에도 유지"나 "한 번에 같이 저장"을 검증할 수 있다.
 * 같은 맵으로 새 목을 다시 만들면 앱 재시작을 흉내 낼 수 있다.
 */
public final class InMemoryPortfolioState {

    private InMemoryPortfolioState() {}

    public static PortfolioStateRepository create(Map<String, Double> values) {
        PortfolioStateRepository repo = mock(PortfolioStateRepository.class);
        when(repo.findById(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return Optional.ofNullable(values.get(key)).map(v -> PortfolioState.of(key, v));
        });
        when(repo.findAllById(anyIterable())).thenAnswer(inv -> {
            List<PortfolioState> found = new ArrayList<>();
            for (Object key : (Iterable<?>) inv.getArgument(0)) {
                Double v = values.get((String) key);
                if (v != null) found.add(PortfolioState.of((String) key, v));
            }
            return found;
        });
        when(repo.save(any(PortfolioState.class))).thenAnswer(inv -> {
            PortfolioState s = inv.getArgument(0);
            values.put(s.getStateKey(), s.getStateValue());
            return s;
        });
        when(repo.saveAll(anyIterable())).thenAnswer(inv -> {
            List<PortfolioState> saved = new ArrayList<>();
            for (Object o : (Iterable<?>) inv.getArgument(0)) {
                PortfolioState s = (PortfolioState) o;
                values.put(s.getStateKey(), s.getStateValue());
                saved.add(s);
            }
            return saved;
        });
        return repo;
    }
}
