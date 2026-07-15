package com.trading.dashboard;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 검토종목 대시보드 API — 유니버스 종목별 변동성 돌파 검토 현황 (읽기 전용).
 */
@RestController
@RequestMapping("/api/review")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping("/candidates")
    public List<Map<String, Object>> getCandidates() {
        return reviewService.candidates();
    }
}
