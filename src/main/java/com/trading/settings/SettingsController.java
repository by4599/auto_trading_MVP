package com.trading.settings;

import com.trading.HeartbeatProperties;
import com.trading.market.KisApiClient;
import com.trading.market.KisProperties;
import com.trading.research.DartProperties;
import com.trading.risk.TradingStatusManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private static final List<String> KEY_ORDER = List.of(
            "KIS_APPKEY", "KIS_SECRETKEY", "KIS_ACCOUNT_NO",
            "TELEGRAM_BOT_TOKEN", "TELEGRAM_CHAT_ID",
            "DART_API_KEY",
            "HEARTBEAT_URL"
    );

    private static final Set<String> ALLOWED_KEYS = new HashSet<>(KEY_ORDER);

    private final KisProperties kisProperties;
    private final KisApiClient kisApiClient;
    private final TradingStatusManager statusManager;
    private final DartProperties dartProperties;
    private final HeartbeatProperties heartbeatProperties;

    public SettingsController(KisProperties kisProperties,
                               KisApiClient kisApiClient,
                               TradingStatusManager statusManager,
                               DartProperties dartProperties,
                               HeartbeatProperties heartbeatProperties) {
        this.kisProperties = kisProperties;
        this.kisApiClient  = kisApiClient;
        this.statusManager = statusManager;
        this.dartProperties = dartProperties;
        this.heartbeatProperties = heartbeatProperties;
    }

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured",   kisProperties.isConfigured());
        result.put("tradingMode",  statusManager.getCurrentMode().name());
        result.put("timestamp",    Instant.now().toEpochMilli());
        return result;
    }

    @GetMapping("/settings")
    public Map<String, Object> getSettings() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : KEY_ORDER) {
            result.put(key, statusOf(key));
        }
        return result;
    }

    @PostMapping("/settings")
    public Map<String, Object> saveSettings(@RequestBody Map<String, String> body) {
        List<String> saved  = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (String key : KEY_ORDER) {
            String value = body.get(key);
            if (value == null || value.isBlank()) continue;
            if (!ALLOWED_KEYS.contains(key)) {
                errors.add(key + ": 허용되지 않는 키");
                continue;
            }
            try {
                runSetx(key, value.trim());
                applyInMemory(key, value.trim());
                saved.add(key);
                log.info("환경변수 저장: {}", key);
            } catch (Exception ex) {
                log.error("setx 실패: {} — {}", key, ex.getMessage());
                errors.add(key + ": " + ex.getMessage());
            }
        }

        // KIS 관련 키가 하나라도 저장됐으면 RestClient·토큰 캐시 즉시 재초기화
        boolean kisChanged = saved.stream().anyMatch(k -> k.startsWith("KIS_"));
        if (kisChanged) {
            kisApiClient.reconfigure();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved",           saved);
        result.put("errors",          errors);
        result.put("configured",      kisProperties.isConfigured());
        result.put("restartRequired", false); // 재시작 없이 즉시 반영됨
        return result;
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────────

    private void applyInMemory(String key, String value) {
        switch (key) {
            case "KIS_APPKEY"          -> kisProperties.setAppkey(value);
            case "KIS_SECRETKEY"       -> kisProperties.setSecretkey(value);
            case "KIS_ACCOUNT_NO"      -> kisProperties.setAccountNo(value);
            case "DART_API_KEY"        -> dartProperties.setApiKey(value);
            case "HEARTBEAT_URL"       -> heartbeatProperties.setUrl(value);
            // TELEGRAM_* 는 별도 컴포넌트가 직접 env 읽음 — 여기선 setx만으로 충분
        }
    }

    private Map<String, String> statusOf(String key) {
        String value = System.getenv(key);
        boolean isSet = value != null && !value.isBlank();
        Map<String, String> m = new LinkedHashMap<>();
        m.put("set", String.valueOf(isSet));
        if (isSet && value.length() > 8) {
            m.put("preview", value.substring(0, 4) + "..." + value.substring(value.length() - 4));
        }
        return m;
    }

    // ProcessBuilder로 setx 호출 — 개별 인자 전달이므로 인젝션 불가
    private void runSetx(String key, String value) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("setx", key, value);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            r.lines().forEach(line -> log.debug("setx output: {}", line));
        }
        int exit = p.waitFor();
        if (exit != 0) throw new RuntimeException("setx exit code " + exit);
    }
}
