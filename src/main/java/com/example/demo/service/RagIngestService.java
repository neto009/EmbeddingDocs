package com.example.demo.service;

import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagIngestService {

    private static final int CHUNK_SIZE = 700;
    private static final int BATCH_SIZE = 128;
    private static final String DEFAULT_LANG = "pt";

    private final JdbcTemplate jdbc;

    @Transactional
    public UUID ingestTextOnly(MultipartFile file) throws IOException {
        UUID documentId = this.insertDocumentMetadata(file);

        ByteArrayResource resource = new ByteArrayResource(file.getBytes());
        TikaDocumentReader reader = new TikaDocumentReader(resource);
        List<Document> extractedDocuments = reader.read();

        TokenTextSplitter splitter = TokenTextSplitter.builder()
            .withChunkSize(CHUNK_SIZE)
            .withMinChunkLengthToEmbed(3)
            .withKeepSeparator(true)
            .build();

        List<Document> chunkDocuments = splitter.apply(extractedDocuments);

        int inserted = this.persistChunks(documentId, chunkDocuments);
        log.info("Documento {} ingerido. Chunks inseridos: {}", documentId, inserted);

        return documentId;
    }

    private UUID insertDocumentMetadata(MultipartFile file) {
        return jdbc.queryForObject(
            "INSERT INTO documents (title, source_uri, mime_type, bytes, lang) VALUES (?,?,?,?,?) RETURNING id",
            UUID.class,
            file.getOriginalFilename(),
            null,
            file.getContentType(),
            file.getSize(),
            DEFAULT_LANG
        );
    }

    private int persistChunks(UUID documentId, List<Document> chunks) {
        final String sql = "INSERT INTO document_chunks (document_id, chunk_index, content, token_count) " +
            "VALUES (?, ?, ?, NULL) ON CONFLICT (document_id, chunk_index) DO NOTHING";

        List<String> filtered = chunks.stream()
            .map(Document::getText)
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(s -> StringUtils.isNotBlank(s))
            .filter(distinctPreservingOrder())
            .toList();

        List<Object[]> params = IntStream.range(0, filtered.size())
            .mapToObj(i -> new Object[]{documentId, i, filtered.get(i)})
            .toList();

        int[] totalInserted = {0};
        IntStream.iterate(0, start -> start < params.size(), start -> start + BATCH_SIZE)
            .forEach(start -> {
                int end = Math.min(start + BATCH_SIZE, params.size());
                List<Object[]> sub = params.subList(start, end);
                int[] results = jdbc.batchUpdate(sql, sub);
                totalInserted[0] += countSuccess(results);
            });
        return totalInserted[0];
    }

    private static Predicate<String> distinctPreservingOrder() {
        Set<String> seen = new HashSet<>();
        return seen::add;
    }

    private int countSuccess(int[] results) {
        return (int) Arrays.stream(results).filter(r -> r >= 0).count();
    }
}
