package com.example.demo.service;

import com.example.demo.client.LmStudioEmbeddingClient;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmbeddingJobService {
    private final JdbcTemplate jdbc;
    private final LmStudioEmbeddingClient lm;   // nosso cliente batch

    private static final int DIM = 1024;        // bge-m3 -> 1024
    private static final int BATCH = 8;         // quantos chunks por request
    private static final java.time.Duration BATCH_TIMEOUT = java.time.Duration.ofSeconds(120);

    public void embedDocument(UUID docId) {
        record Row(UUID id, String content) {}
        List<Row> rows = jdbc.query("""
        SELECT id, content
        FROM document_chunks
        WHERE document_id = ? AND embedding IS NULL
        ORDER BY chunk_index
        """, (rs, i) -> new Row((UUID) rs.getObject("id"), rs.getString("content")),
                docId);

        final String up = "UPDATE document_chunks SET embedding = ?::vector WHERE id = ?";
        List<Object[]> batchUpdate = new ArrayList<>(BATCH);

        for (int i = 0; i < rows.size(); i += BATCH) {
            List<Row> group = rows.subList(i, Math.min(i + BATCH, rows.size()));

            // prepara textos (limpa vazios)
            List<String> inputs = new ArrayList<>();
            List<Row> kept = new ArrayList<>();
            for (Row r : group) {
                if (r.content() != null && !r.content().isBlank()) {
                    inputs.add(r.content());
                    kept.add(r);
                }
            }
            if (inputs.isEmpty()) continue;

            // chama LM Studio uma vez para o grupo (evita ECONNRESET por excesso de conexões)
            List<float[]> embs = lm.embedBatch(inputs, "text-embedding-bge-m3", BATCH_TIMEOUT);
            if (embs.size() != kept.size()) {
                // se algo falhar, pula com segurança
                continue;
            }

            // monta os updates
            for (int k = 0; k < kept.size(); k++) {
                String lit = toPgVectorLiteral(embs.get(k));
                batchUpdate.add(new Object[]{ lit, kept.get(k).id() });
            }

            // aplica em lotes moderados
            if (batchUpdate.size() >= BATCH) {
                jdbc.batchUpdate(up, batchUpdate);
                batchUpdate.clear();
            }

            // pequeno descanso entre batches (evita aquecimento/estouro do runtime)
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
        }

        if (!batchUpdate.isEmpty()) {
            jdbc.batchUpdate(up, batchUpdate);
        }
    }

    private static String toPgVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(java.util.Locale.US, "%.8f", v[i]));
        }
        return sb.append(']').toString();
    }
}