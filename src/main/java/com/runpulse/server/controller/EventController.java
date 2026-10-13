package com.runpulse.server.controller;

import com.runpulse.server.dto.EventsRequest;
import com.runpulse.server.exception.ApiException;
import com.runpulse.server.model.ClientEvent;
import com.runpulse.server.repository.RunPulseRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接收 App 端诊断事件。诊断通道必须"永不阻塞、永不报错影响主流程"，
 * 只做最基本的字段校验，落库失败才返回 500。
 */
@RestController
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);

    private static final int MAX_BATCH = 100;

    private final RunPulseRepository repository;

    public EventController(RunPulseRepository repository) {
        this.repository = repository;
    }

    @PostMapping("/api/v1/events")
    public Map<String, Object> events(@RequestBody(required = false) EventsRequest req) {
        if (req == null || req.events() == null || req.events().isEmpty()) {
            throw ApiException.badRequest("bad request");
        }
        List<ClientEvent> events = req.events();
        if (events.size() > MAX_BATCH) {
            throw ApiException.badRequest("batch too large");
        }
        for (ClientEvent e : events) {
            if (e == null || e.event == null || e.event.isEmpty()) {
                throw ApiException.badRequest("event required");
            }
            // Go 版本缺失 detail 时写入空字符串；同时截断保护，防异常客户端写入超长内容。
            if (e.detail == null) {
                e.detail = "";
            } else if (e.detail.length() > 1024) {
                e.detail = e.detail.substring(0, 1024);
            }
        }
        try {
            repository.insertEvents(events);
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        }
        for (ClientEvent e : events) {
            String sid = e.sessionId != null ? e.sessionId : "-";
            log.info("client_event session={} event={} detail={}", sid, e.event, e.detail);
        }
        return Map.of("received", events.size());
    }
}
