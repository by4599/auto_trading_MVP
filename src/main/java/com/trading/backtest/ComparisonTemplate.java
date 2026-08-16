package com.trading.backtest;

import java.util.List;

/**
 * 랩 비교 리포트의 <b>고정 문구</b> — 제목·파일 접두사·스윕 설명·비용 모델·판독 지침.
 *
 * <p>랩마다 다른 것은 이 다섯 덩어리뿐이고 표를 그리는 코드는 같다
 * ({@link LabComparisonReportWriter}). 문구는 {@link LabReportTemplates}에 모여 있다.
 */
record ComparisonTemplate(String titlePhrase, String filePrefix, String sweepDescLine,
                          String costModelLine, List<String> readingGuide) {}
