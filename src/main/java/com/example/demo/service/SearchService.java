package com.example.demo.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SearchService {

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;

    public List<Result> hybridSearch(String question, int topK, double alpha, int perDocLimit) {
        // 1) embedding da pergunta
        float[] v = embeddingModel.embed(question);
        String vec = toPgVectorLiteral(v);

        // 2) SQL: pré-seleciona por vetor (LIMIT 200), re-ranqueia com FTS e limita por documento
        String sql = """
            WITH q AS (
              SELECT ?::vector AS v,
                     websearch_to_tsquery('portuguese', unaccent(?)) AS tsq
            ),
            vec AS (
              SELECT c.*,
                     (1 - (c.embedding <=> (SELECT v FROM q))) AS vsim
              FROM document_chunks c
              WHERE c.embedding IS NOT NULL
              ORDER BY c.embedding <=> (SELECT v FROM q) ASC
              LIMIT 200
            ),
            scored AS (
              SELECT v.id, v.document_id, v.chunk_index, v.content,
                     v.vsim,
                     ts_rank(v.tsv, (SELECT tsq FROM q)) AS fr,
                     ( ? * v.vsim + (1 - ?) * ts_rank(v.tsv, (SELECT tsq FROM q)) ) AS score,
                     row_number() OVER (
                       PARTITION BY v.document_id
                       ORDER BY ( ? * v.vsim + (1 - ?) * ts_rank(v.tsv, (SELECT tsq FROM q)) ) DESC
                     ) AS rnk_in_doc
              FROM vec v
            )
            SELECT id, document_id, chunk_index, content, vsim, fr, score
            FROM scored
            WHERE rnk_in_doc <= ?
            ORDER BY score DESC
            LIMIT ?
            """;

        return jdbc.query(sql, (rs, i) -> new Result(
                    (UUID) rs.getObject("id"),
                    (UUID) rs.getObject("document_id"),
                    rs.getInt("chunk_index"),
                    rs.getString("content"),
                    rs.getDouble("vsim"),
                    rs.getDouble("fr"),
                    rs.getDouble("score")
                ),
                vec,                          // q.v
                question,                     // q.tsq (websearch_to_tsquery + unaccent)
                alpha, alpha,                 // pesos
                alpha, alpha,                 // mesmos pesos no ORDER BY da janela
                perDocLimit,                  // no máximo N chunks por documento
                topK                          // top-K final
        );
    }

    public List<String> searchTopDocsFullContents(String question, int topDocs) {
        // 1) Embedding da pergunta (ajuste para o seu cliente real)
        float[] qvec = embeddingModel.embed(question);
        String vec = toPgVectorLiteral(qvec);

        // Peso híbrido: w (vector) e (1 - w) (texto)
        double wVec = 0.7;
        double wTxt = 1.0 - wVec;

        // 2) SQL híbrido corrigido:
        // - Usa websearch_to_tsquery('simple', ?) para construir o TSQUERY da pergunta
        // - Faz CAST do parâmetro do vetor com ::vector em TODAS as ocorrências
        // - Usa row_number para pegar o melhor chunk por documento e depois ordena por score
        final String sql = """
        WITH q AS (
            SELECT websearch_to_tsquery('simple', ?) AS tsq
        ),
        scored AS (
            SELECT
                c.document_id,
                (
                    ? * (1 - (c.embedding <=> ?::vector))  +
                    ? * ts_rank(c.tsv, (SELECT tsq FROM q))
                ) AS score,
                row_number() OVER (
                    PARTITION BY c.document_id
                    ORDER BY (
                        ? * (1 - (c.embedding <=> ?::vector)) +
                        ? * ts_rank(c.tsv, (SELECT tsq FROM q))
                    ) DESC
                ) AS rnk_in_doc
            FROM document_chunks c
        )
        SELECT document_id
        FROM scored
        WHERE rnk_in_doc = 1
        ORDER BY score DESC
        LIMIT ?
    """;

        // Ordem dos parâmetros deve bater com a SQL acima:
        // 1: question (para tsquery)
        // 2: wVec
        // 3: vec (::vector)
        // 4: wTxt
        // 5: wVec
        // 6: vec (::vector)
        // 7: wTxt
        // 8: topDocs
        List<UUID> docIds = jdbc.query(
                sql,
                (rs, i) -> (UUID) rs.getObject("document_id"),
                question,
                wVec, vec, wTxt,
                wVec, vec, wTxt,
                topDocs
        );

        // 3) Reconstrói o conteúdo completo de cada documento
        List<String> out = new ArrayList<>(docIds.size());
        for (UUID id : new java.util.LinkedHashSet<>(docIds)) { // preserva ordem, remove duplicatas
            String content = getFullDocumentContent(id);
            if (content != null && !content.isBlank()) {
                out.add(content);
                if (out.size() >= topDocs) break;
            }
        }
        return out;
    }
    /**
     * Recupera o documento completo por ID, concatenando todos os chunks
     */
    public String getFullDocumentContent(UUID documentId) {
        String sql = """
            SELECT content 
            FROM document_chunks 
            WHERE document_id = ? 
            ORDER BY chunk_index
            """;
        
        List<String> chunks = jdbc.queryForList(sql, String.class, documentId);
        return String.join(" ", chunks);
    }

    /**
     * Recupera informações do documento (título, etc.)
     */
    public DocumentInfo getDocumentInfo(UUID documentId) {
        String sql = """
            SELECT id, title, source_uri, mime_type, bytes, lang, created_at
            FROM documents 
            WHERE id = ?
            """;
        
        try {
            return jdbc.queryForObject(sql, (rs, i) -> new DocumentInfo(
                (UUID) rs.getObject("id"),
                rs.getString("title"),
                rs.getString("source_uri"),
                rs.getString("mime_type"),
                rs.getLong("bytes"),
                rs.getString("lang"),
                rs.getTimestamp("created_at").toInstant()
            ), documentId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null; // Documento não encontrado
        }
    }

    private static String toPgVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 12 + 2);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.US, "%.8f", v[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    public record Result(UUID id, UUID documentId, int chunkIndex, String content,
         double vsim, double fr, double score) {}

    public record DocumentInfo(UUID id, String title, String sourceUri, String mimeType, 
         long bytes, String lang, java.time.Instant createdAt) {}
}
