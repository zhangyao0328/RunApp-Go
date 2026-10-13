package com.runpulse.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.runpulse.server.config.RunpulseProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 JDK HttpServer mock OpenClaw（OpenAI 兼容）/chat/completions 接口。
 */
class OpenClawClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer server;
    private OpenClawClient client;

    private final AtomicReference<String> capturedPath = new AtomicReference<>();
    private final AtomicReference<String> capturedAuth = new AtomicReference<>();
    private final AtomicReference<String> capturedMethod = new AtomicReference<>();
    private final AtomicReference<JsonNode> capturedBody = new AtomicReference<>();
    private volatile String responseJson = "{}";

    @BeforeEach
    void startServer() throws IOException {
        capturedPath.set(null);
        capturedAuth.set(null);
        capturedMethod.set(null);
        capturedBody.set(null);
        responseJson = "{}";

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            capturedMethod.set(exchange.getRequestMethod());
            capturedPath.set(exchange.getRequestURI().getPath());
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try (InputStream in = exchange.getRequestBody()) {
                capturedBody.set(objectMapper.readTree(in));
            }
            byte[] out = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        MockEnvironment env = new MockEnvironment()
                .withProperty("OPENCLAW_BASE_URL", baseUrl)
                .withProperty("OPENCLAW_API_KEY", "test-key-123")
                .withProperty("OPENCLAW_MODEL", "test-model");
        client = new OpenClawClient(new RunpulseProperties(env), objectMapper);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void successSendsExpectedRequestAndReturnsContent() throws Exception {
        responseJson = "{\"choices\":[{\"message\":{\"content\":\"心率正常，保持当前配速\"}}]}";

        String result = client.chat("你是教练", "summary data");

        // 返回第一个 choice 的 message.content
        assertEquals("心率正常，保持当前配速", result);

        // 请求打到 {baseUrl}/chat/completions
        assertEquals("POST", capturedMethod.get());
        assertEquals("/chat/completions", capturedPath.get());

        // Authorization 头
        assertEquals("Bearer test-key-123", capturedAuth.get());

        // body 中的 model 与 messages
        JsonNode body = capturedBody.get();
        assertEquals("test-model", body.get("model").asText());
        JsonNode messages = body.get("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).get("role").asText());
        assertEquals("你是教练", messages.get(0).get("content").asText());
        assertEquals("user", messages.get(1).get("role").asText());
        assertEquals("summary data", messages.get(1).get("content").asText());
    }

    @Test
    void emptyChoicesThrows() {
        responseJson = "{\"choices\":[]}";
        IOException ex = assertThrows(IOException.class, () -> client.chat("sys", "user"));
        assertTrue(ex.getMessage().contains("空 choices"), "unexpected: " + ex.getMessage());
    }

    @Test
    void missingChoicesThrows() {
        responseJson = "{}";
        IOException ex = assertThrows(IOException.class, () -> client.chat("sys", "user"));
        assertTrue(ex.getMessage().contains("空 choices"), "unexpected: " + ex.getMessage());
    }

    @Test
    void nonJsonResponseThrows() {
        responseJson = "not-json-at-all";
        assertThrows(Exception.class, () -> client.chat("sys", "user"));
    }
}
