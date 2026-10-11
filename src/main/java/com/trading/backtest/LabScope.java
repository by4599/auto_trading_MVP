package com.trading.backtest;

import java.time.LocalDate;
import java.util.List;

/**
 * 랩 1회 실행의 고정 범위 — 표기용 라벨/파일 슬러그 + 유니버스 + 기간.
 *
 * <p>랩 메서드마다 (label, slug, symbols, from, to) 다섯 값을 함께 끌고 다니던 것을 묶은 것이다.
 */
record LabScope(String label, String slug, List<String> symbols, LocalDate from, LocalDate to) {

    /** 같은 유니버스·기간에 표기 이름만 바꾼 범위 (리포트 제목이 판정 라벨과 다를 때) */
    LabScope withNames(String newLabel, String newSlug) {
        return new LabScope(newLabel, newSlug, symbols, from, to);
    }
}
