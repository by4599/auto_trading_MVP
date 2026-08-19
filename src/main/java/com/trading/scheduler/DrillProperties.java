package com.trading.scheduler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 예약 청산 리허설 설정.
 *
 * 기본 꺼짐 — 켜는 것은 사람의 결정이다. 켜더라도 날짜를 콕 집어야 돌아간다(상시 반복 없음):
 * 리허설은 보유분을 전부 팔고 매매를 멈추는 조작이라, 매 거래일 자동 반복은 매매 자체를
 * 못 하게 만든다.
 */
@Component
public class DrillProperties {

    private final boolean enabled;
    private final String  date;
    private final String  at;
    private final String  deadline;
    private final boolean buyIfFlat;

    public DrillProperties(
            @Value("${trading.drill.enabled:false}") boolean enabled,
            @Value("${trading.drill.date:}")         String date,
            @Value("${trading.drill.at:10:00}")      String at,
            @Value("${trading.drill.deadline:14:00}") String deadline,
            @Value("${trading.drill.buy-if-flat:true}") boolean buyIfFlat) {
        this.enabled   = enabled;
        this.date      = date;
        this.at        = at;
        this.deadline  = deadline;
        this.buyIfFlat = buyIfFlat;
    }

    public boolean isEnabled()   { return enabled; }
    public boolean isBuyIfFlat() { return buyIfFlat; }

    /** 예약일 — 미설정이면 null (그 경우 스케줄러는 아무것도 하지 않는다) */
    public LocalDate getDate() {
        return (date == null || date.isBlank()) ? null : LocalDate.parse(date.trim());
    }

    /** 개시 시각 */
    public LocalTime getAt() { return LocalTime.parse(at.trim()); }

    /**
     * 만회 기한 — 개시 시각에 앱이 꺼져 있었거나 조건이 안 맞았어도 이 시각까지는 다시 시도한다.
     * 타임컷(15:15)보다 넉넉히 앞에 둬야 리허설과 타임컷이 겹치지 않는다.
     */
    public LocalTime getDeadline() { return LocalTime.parse(deadline.trim()); }
}
