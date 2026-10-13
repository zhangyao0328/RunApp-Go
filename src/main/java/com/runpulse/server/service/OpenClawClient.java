package com.runpulse.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runpulse.server.config.RunpulseProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * OpenClaw（OpenAI 兼容）客户端，对应 Go 版本 callOpenClaw。
 * 单次 HTTP 请求 60s 超时，整体（含调度）90s 超时。
 */
public class OpenClawClient {

    private static final long HTTP_TIMEOUT_SEC = 60;
    private static final long OVERALL_TIMEOUT_SEC = 90;

    private final RunpulseProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenClawClient(RunpulseProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** 调用云端 OpenClaw 的 /chat/completions 接口。 */
    public String chat(String system, String user) throws Exception {
        Map<String, Object> body = Map.of(
                "model", props.getOpenclawModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)));

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(props.getOpenclawBaseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SEC))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        if (!props.getOpenclawApiKey().isEmpty()) {
            builder.header("Authorization", "Bearer " + props.getOpenclawApiKey());
        }
        HttpRequest request = builder.build();

        // 整体 90s 超时（对应 Go context.WithTimeout 90s）
        CompletableFuture<HttpResponse<byte[]>> future =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        HttpResponse<byte[]> resp;
        try {
            resp = future.get(OVERALL_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new IOException("openclaw 调用超过 90s 整体超时", e);
        }

        // 响应体最多读 1MB（对应 Go io.LimitReader(resp.Body, 1<<20)）
        byte[] raw = resp.body();
        int limit = Math.min(raw.length, 1 << 20);
        String text = new String(raw, 0, limit, java.nio.charset.StandardCharsets.UTF_8);
        if (resp.statusCode() != 200) {
            throw new IOException(String.format("openclaw status %d: %s", resp.statusCode(), text));
        }
        JsonNode root = objectMapper.readTree(text);
        JsonNode choices = root.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IOException("openclaw 返回空 choices");
        }
        JsonNode content = choices.get(0).path("message").path("content");
        return content.asText("");
    }
}
