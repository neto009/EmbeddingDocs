package com.example.demo.client;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class LmStudioEmbeddingClient {

    private final EmbeddingModel embeddingModel;

    /**
     * Gera embeddings em lote para a lista de textos informada.
     * @param texts  lista de textos de entrada; se vazia ou nula retorna lista vazia
     * @param model  nome do modelo a ser utilizado (não deve ser nulo)
     * @return lista imutável de vetores de floats (um por texto); vazia em caso de erro ou ausência de dados
     * @throws org.springframework.web.reactive.function.client.WebClientResponseException em falhas HTTP (não tratadas aqui)
     * @throws java.util.concurrent.TimeoutException se a operação exceder o tempo definido
     */
    public List<float[]> embedBatch(List<String> texts, String model) {
        EmbeddingRequest req = new EmbeddingRequest(texts, null);
        EmbeddingResponse resp = embeddingModel.call(req);

        return resp.getResults().stream()
            .filter(Objects::nonNull)
            .map(d -> d.getOutput())
            .toList();
    }
}
