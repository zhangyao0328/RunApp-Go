package com.runpulse.server.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * 轨迹点。对应 Go 版本 store.go 的 TrackPoint。
 * altitude/speed/accuracy 为包装类型以保留 null（JSON 中可缺省）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TrackPoint {

    @JsonIgnore
    public long id;

    @JsonIgnore
    public String sessionId;

    public double lat;
    public double lng;
    public Double altitude;
    public Double speed;
    public Double accuracy;
    public Integer heartRate;
    public Integer cadence; // 步频 SPM
    public long timestamp;  // 毫秒时间戳
}
