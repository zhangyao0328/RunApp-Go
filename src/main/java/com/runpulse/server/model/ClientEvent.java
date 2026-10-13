package com.runpulse.server.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * App 端上报的诊断事件（进程重启、Service 生命周期、定位断流等）。
 * 对应 Go 版本 ClientEvent，全部字段宽松处理，不影响主流程。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClientEvent {

    public String sessionId;
    public String event;
    public String detail;
    public long clientTs;
}
