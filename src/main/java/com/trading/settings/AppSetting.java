package com.trading.settings;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 사용자가 설정 UI에서 변경한 투자 파라미터 (key-value).
 *
 * DB에 행이 있는 파라미터만 코드 기본값/yml 값을 덮어쓴다 —
 * 행이 없으면 해당 파라미터는 건드리지 않는다 (TradingParamService.loadOnStartup).
 * API 키(setx 영속화)와 달리 개수가 많고 재시작 반영이 필요 없어 H2에 저장한다.
 */
@Entity
@Table(name = "app_setting")
public class AppSetting {

    @Id
    @Column(name = "param_key", length = 64)
    private String paramKey;

    @Column(name = "param_value", nullable = false, length = 32)
    private String paramValue;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected AppSetting() {}

    public static AppSetting of(String paramKey, String paramValue) {
        AppSetting s = new AppSetting();
        s.paramKey   = paramKey;
        s.paramValue = paramValue;
        s.updatedAt  = LocalDateTime.now();
        return s;
    }

    public String getParamKey()       { return paramKey; }
    public String getParamValue()     { return paramValue; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void updateValue(String value) {
        this.paramValue = value;
        this.updatedAt  = LocalDateTime.now();
    }
}
