package com.example.demo.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SearchService {

    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;

    public List<Result> hybridSearch(String question, int topK, int perDocLimit) {
        float[] v = embeddingModel.embed(question);
        String vec = toPgVectorLiteral(v);

        String sql = """
            WITH q AS (
              SELECT ?::vector AS v
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
                     0.0 AS fr,                     -- sem FTS
                     v.vsim AS score,
                     row_number() OVER (
                       PARTITION BY v.document_id
                       ORDER BY v.vsim DESC
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
            vec,                 // q.v
            perDocLimit,         // no máximo N chunks por documento
            topK                 // top-K final
        );
    }

    public List<String> searchTopDocsFullContents(String question, int topDocs) {
        float[] qvec = embeddingModel.embed(question);
        String vec = toPgVectorLiteral(qvec);

        final String sql = """
        WITH q AS (
            SELECT ?::vector AS v
        ),
        scored AS (
            SELECT
                c.document_id,
                (1 - (c.embedding <=> (SELECT v FROM q))) AS score,
                row_number() OVER (
                    PARTITION BY c.document_id
                    ORDER BY (1 - (c.embedding <=> (SELECT v FROM q))) DESC
                ) AS rnk_in_doc
            FROM document_chunks c
            WHERE c.embedding IS NOT NULL
        )
        SELECT document_id
        FROM scored
        WHERE rnk_in_doc = 1
        ORDER BY score DESC
        LIMIT ?
        """;

        List<UUID> docIds = jdbc.query(
            sql,
            (rs, i) -> (UUID) rs.getObject("document_id"),
            vec,
            topDocs
        );

        List<String> out = new ArrayList<>(docIds.size());
        for (UUID id : new java.util.LinkedHashSet<>(docIds)) {
            String content = getFullDocumentContent(id);
            if (content != null && !content.isBlank()) {
                out.add(content);
                if (out.size() >= topDocs) break;
            }
        }
        return out;
    }

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
            return null;
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
