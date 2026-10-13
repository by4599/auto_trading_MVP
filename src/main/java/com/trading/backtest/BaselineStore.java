package com.trading.backtest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 거버넌스 기준선 yml <b>읽기</b> 경로 — 앵커 수치의 정본은 이 파일 하나다.
 *
 * <p>쓰기는 {@link BacktestReportWriter#writeBaseline}만 한다(파일 머리글의 "수동 편집 금지"
 * 성격 유지). 여기서는 그 파일을 다시 읽어 실행 결과와 대조할 수 있게만 한다 — 문서·코드에
 * 앵커 숫자를 다시 적어 두고 사람이 눈으로 맞춰 보던 일을 없애는 것이 목적이다.
 *
 * <p>파서는 이 자동 생성 포맷(고정 13줄, 중첩 1단계, 키 중복 없음)에 맞춘 최소 구현이다.
 * 외부 YAML 라이브러리를 쓰지 않는 이유는 두 가지다: ① 대조는 <b>파일에 적힌 문자열
 * 그대로</b>(반올림된 자릿수) 해야 하는데 라이브러리는 double로 되돌려 자릿수를 잃는다,
 * ② 이 파일은 우리가 생성하는 고정 포맷이라 일반 YAML 문법을 감당할 필요가 없다.
 */
@Component
@Profile("backtest")
public class BaselineStore {

    private static final Logger log = LoggerFactory.getLogger(BaselineStore.class);

    static final Path DOCS_DIR = Path.of("docs");

    /** {@link BacktestReportWriter#writeBaseline}과 같은 이름 규칙 — 한 곳에서만 정한다 */
    static String fileName(String fileSlug) {
        return fileSlug == null || fileSlug.isEmpty()
                ? "BACKTEST-BASELINE.yml" : "BACKTEST-BASELINE-" + fileSlug + ".yml";
    }

    /** 없으면 Optional.empty (첫 기록 실행 — 대조할 앵커가 아직 없다) */
    public Optional<BaselineSnapshot> load(String fileSlug) {
        return read(DOCS_DIR.resolve(fileName(fileSlug)));
    }

    static Optional<BaselineSnapshot> read(Path file) {
        if (!Files.exists(file)) {
            log.info("[Baseline] 기준선 파일 없음 — 앵커 대조 생략: {}", file);
            return Optional.empty();
        }
        try {
            Optional<BaselineSnapshot> parsed = parse(Files.readAllLines(file));
            if (parsed.isEmpty()) {
                log.warn("[Baseline] ⚠ 기준선 파일을 해석하지 못했다 — 대조 생략: {}", file);
            }
            return parsed;
        } catch (IOException e) {
            log.warn("[Baseline] ⚠ 기준선 파일 읽기 실패 — 대조 생략: {} ({})", file, e.getMessage());
            return Optional.empty();
        }
    }

    /** 자동 생성 포맷 전용 최소 파서 — 해석 실패는 예외가 아니라 빈 값(대조는 부가 기능이다) */
    static Optional<BaselineSnapshot> parse(List<String> lines) {
        Map<String, String> kv = new HashMap<>();
        for (String raw : lines) {
            String line = stripComment(raw).trim();
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            kv.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        try {
            String[] period = kv.getOrDefault("period", "").split("~");
            if (period.length != 2) return Optional.empty();
            return Optional.of(new BaselineSnapshot(
                    LocalDate.parse(period[0].trim()),
                    LocalDate.parse(period[1].trim()),
                    Integer.parseInt(require(kv, "trades")),
                    require(kv, "profit-factor"),
                    require(kv, "expectancy-pct"),
                    require(kv, "max-drawdown"),
                    require(kv, "win-rate")));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String require(Map<String, String> kv, String key) {
        String value = kv.get(key);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("기준선 키 없음: " + key);
        return value;
    }

    /** '#' 이후는 주석 — 값에 '#'가 들어가는 항목이 없는 포맷이라 이 단순 규칙으로 충분하다 */
    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }
}
