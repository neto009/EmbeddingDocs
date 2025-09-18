package com.example.demo.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@Component
public class LmStudioChatClient {

    private final WebClient webClient;
    private final ObjectMapper mapper;

    public static record ChatCallResult(boolean ok, String content, String err, String _req, String _raw) {
        public static ChatCallResult ok(String content, String reqJson, String raw) {
            return new ChatCallResult(true, content, null, reqJson, raw);
        }
        public static ChatCallResult fail(String err, String reqJson, String raw) {
            return new ChatCallResult(false, null, err, reqJson, raw);
        }
    }

    public LmStudioChatClient(WebClient lmStudioWebClient, ObjectMapper mapper) {
        this.webClient = lmStudioWebClient;
        this.mapper = mapper;
    }

    public ChatCallResult chat(Map<String, Object> payload, int timeoutSeconds) {
        String reqJson;
        try {
            reqJson = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return ChatCallResult.fail("serialize error: " + e.getMessage(), null, null);
        }

        try {
            String body = webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .onStatus(s -> !s.is2xxSuccessful(),
                    resp -> resp.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(b -> Mono.error(new RuntimeException("HTTP " + resp.statusCode().value() + " - " + b))))
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(Math.max(timeoutSeconds, 1)));

            JsonNode root = mapper.readTree(body);
            String content = root.path("choices").path(0).path("message").path("content").asText(null);
            return ChatCallResult.ok(content, reqJson, body);
        } catch (Exception e) {
            return ChatCallResult.fail(e.getMessage(), reqJson, null);
        }
    }
}
