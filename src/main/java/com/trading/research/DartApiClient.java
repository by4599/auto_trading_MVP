package com.trading.research;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * DART Open API 접근 인터페이스 — HTTP 상세를 숨겨 수집 로직(DartDisclosureService)을
 * 단위 테스트 가능하게 한다 (Java 25 Mockito는 인터페이스만 목킹 가능).
 */
public interface DartApiClient {

    boolean isConfigured();

    /** 상장사 전체의 종목코드 → (DART corp_code, 회사명) 매핑. 미설정/실패 시 빈 맵. */
    Map<String, CorpInfo> fetchCorpCodeMap();

    /** 특정 회사의 기간 내 공시 목록 (최신순). 없으면 빈 리스트. */
    List<DartDisclosure> fetchRecentDisclosures(String corpCode, LocalDate from, LocalDate to);

    record CorpInfo(String corpCode, String corpName) {}

    record DartDisclosure(String receiptNo, String reportName, String corpName, LocalDate disclosedAt) {}
}
