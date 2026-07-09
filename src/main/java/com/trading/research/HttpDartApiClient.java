package com.trading.research;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * DART Open API 실구현.
 *
 * corpCode.xml: ZIP(CORPCODE.xml) — StAX 스트리밍 파싱 (10만 상장·비상장 엔트리,
 * DOM은 메모리 낭비). 상장사(stock_code 있는 것)만 매핑에 담는다.
 * list.json: 회사별 최근 공시 목록. status "013" = 조회 결과 없음 (정상).
 */
@Component
public class HttpDartApiClient implements DartApiClient {

    private static final Logger log = LoggerFactory.getLogger(HttpDartApiClient.class);

    private static final String BASE_URL = "https://opendart.fss.or.kr";
    private static final DateTimeFormatter DART_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final DartProperties dartProperties;
    private final RestClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HttpDartApiClient(DartProperties dartProperties) {
        this.dartProperties = dartProperties;
        this.httpClient = RestClient.builder().baseUrl(BASE_URL).build();
    }

    @Override
    public boolean isConfigured() {
        return dartProperties.isConfigured();
    }

    @Override
    public Map<String, CorpInfo> fetchCorpCodeMap() {
        if (!isConfigured()) return Map.of();
        try {
            byte[] zip = httpClient.get()
                    .uri(b -> b.path("/api/corpCode.xml")
                            .queryParam("crtfc_key", dartProperties.getApiKey())
                            .build())
                    .retrieve()
                    .body(byte[].class);
            if (zip == null || zip.length == 0) {
                log.warn("[DART] corpCode.xml 응답 없음");
                return Map.of();
            }
            Map<String, CorpInfo> map = parseCorpCodeZip(zip);
            log.info("[DART] 상장사 코드 매핑 적재: {}건", map.size());
            return map;
        } catch (Exception e) {
            log.error("[DART] corpCode.xml 수집 실패: {}", e.getMessage());
            return Map.of();
        }
    }

    @Override
    public List<DartDisclosure> fetchRecentDisclosures(String corpCode, LocalDate from, LocalDate to) {
        if (!isConfigured()) return List.of();
        try {
            String json = httpClient.get()
                    .uri(b -> b.path("/api/list.json")
                            .queryParam("crtfc_key",  dartProperties.getApiKey())
                            .queryParam("corp_code",  corpCode)
                            .queryParam("bgn_de",     from.format(DART_DATE))
                            .queryParam("end_de",     to.format(DART_DATE))
                            .queryParam("page_count", "100")
                            .build())
                    .retrieve()
                    .body(String.class);
            if (json == null) return List.of();

            ListResponse resp = objectMapper.readValue(json, ListResponse.class);
            if ("013".equals(resp.status())) return List.of();   // 조회 결과 없음 (정상)
            if (!"000".equals(resp.status())) {
                log.warn("[DART] list.json 오류: status={} message={}", resp.status(), resp.message());
                return List.of();
            }
            if (resp.list() == null) return List.of();

            List<DartDisclosure> result = new ArrayList<>();
            for (ListEntry e : resp.list()) {
                if (e.receiptNo() == null || e.reportName() == null || e.receiptDate() == null) continue;
                result.add(new DartDisclosure(
                        e.receiptNo(), e.reportName().strip(), e.corpName(),
                        LocalDate.parse(e.receiptDate(), DART_DATE)));
            }
            return result;
        } catch (Exception e) {
            log.warn("[DART] 공시 조회 실패 (corpCode={}): {}", corpCode, e.getMessage());
            return List.of();
        }
    }

    // ── corpCode.xml ZIP 파싱 ─────────────────────────────────────────────────

    private static Map<String, CorpInfo> parseCorpCodeZip(byte[] zipBytes) throws Exception {
        Map<String, CorpInfo> map = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.getName().toUpperCase().endsWith(".XML")) continue;

                XMLInputFactory factory = XMLInputFactory.newInstance();
                factory.setProperty(XMLInputFactory.SUPPORT_DTD, false); // XXE 방어
                factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
                XMLStreamReader reader = factory.createXMLStreamReader(zis);

                String tag = null, corpCode = null, corpName = null, stockCode = null;
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        tag = reader.getLocalName();
                        if ("list".equals(tag)) { corpCode = corpName = stockCode = null; }
                    } else if (event == XMLStreamConstants.CHARACTERS && tag != null) {
                        String text = reader.getText().strip();
                        if (text.isEmpty()) continue;
                        switch (tag) {
                            case "corp_code"  -> corpCode  = text;
                            case "corp_name"  -> corpName  = text;
                            case "stock_code" -> stockCode = text;
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if ("list".equals(reader.getLocalName())) {
                            if (stockCode != null && stockCode.length() == 6 && corpCode != null) {
                                map.put(stockCode, new CorpInfo(corpCode, corpName));
                            }
                        }
                        tag = null;
                    }
                }
                break; // CORPCODE.xml 하나만
            }
        }
        return map;
    }

    // ── DART 응답 DTO ─────────────────────────────────────────────────────────

    private record ListResponse(
            @JsonProperty("status")  String status,
            @JsonProperty("message") String message,
            @JsonProperty("list")    List<ListEntry> list
    ) {}

    private record ListEntry(
            @JsonProperty("rcept_no")  String receiptNo,
            @JsonProperty("report_nm") String reportName,
            @JsonProperty("corp_name") String corpName,
            @JsonProperty("rcept_dt")  String receiptDate
    ) {}
}
