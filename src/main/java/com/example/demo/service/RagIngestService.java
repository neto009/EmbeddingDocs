package com.example.demo.service;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RagIngestService {
    private final JdbcTemplate jdbc;

    @Transactional
    public UUID ingestTextOnly(MultipartFile file) throws IOException {
        UUID docId = jdbc.queryForObject(
                "INSERT INTO documents (title, source_uri, mime_type, bytes, lang) VALUES (?,?,?,?,?) RETURNING id",
                UUID.class,
                file.getOriginalFilename(), null, file.getContentType(), file.getSize(), "pt"
        );

        // Extrai e divide
        var res = new ByteArrayResource(file.getBytes());
        var docs = new TikaDocumentReader(res).read();
        var splitter = TokenTextSplitter.builder()
            .withChunkSize(600)
            .withMinChunkLengthToEmbed(3)
            .withKeepSeparator(true)
            .build();
        var chunks = splitter.apply(docs);

        final String sql = """
            INSERT INTO document_chunks (document_id, chunk_index, content, char_start, char_end, token_count)
            VALUES (?, ?, ?, NULL, NULL, NULL)
            ON CONFLICT (document_id, content_hash) DO NOTHING
            """;

        List<Object[]> batch = new ArrayList<>();
        int idx = 0;
        for (var ch : chunks) {
            String text = ch.getText();
            if (text == null || text.isBlank()) continue;
            batch.add(new Object[]{ docId, idx++, text });
            if (batch.size() == 128) { jdbc.batchUpdate(sql, batch); batch.clear(); }
        }
        if (!batch.isEmpty()) jdbc.batchUpdate(sql, batch);

        // Opcional: atualiza estatísticas p/ FTS
        jdbc.execute("ANALYZE document_chunks");

        return docId;
    }
}
