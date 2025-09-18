package com.example.demo.client;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LmStudioEmbeddingClient {

    private final WebClient lmStudioWebClient;

    public List<float[]> embedBatch(List<String> texts, String model, java.time.Duration timeout) {
        EmbeddingRequest req = new EmbeddingRequest(model, texts);

        EmbeddingResponse resp = lmStudioWebClient.post()
            .uri("/embeddings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(req)
            .retrieve()
            .bodyToMono(EmbeddingResponse.class)
            .timeout(timeout)
            .block();

        if (resp == null || resp.data == null) return List.of();

        List<float[]> out = new ArrayList<>(resp.data.size());
        for (EmbeddingResponse.Data d : resp.data) {
            float[] v = new float[d.embedding.size()];
            for (int i = 0; i < v.length; i++) v[i] = d.embedding.get(i).floatValue();
            out.add(v);
        }
        return out;
    }

    // OpenAI-like schema
    public record EmbeddingRequest(String model, List<String> input) {}
    public static final class EmbeddingResponse {
        public List<Data> data;
        public static final class Data { public List<Double> embedding; }
    }
}
