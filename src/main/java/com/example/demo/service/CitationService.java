package com.example.demo.service;

import com.example.demo.config.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CitationService {

    private final RagProperties ragProperties;

    public List<RAGAnswerService.Citation> createCitations(List<SearchService.Result> hits) {
        return hits.stream()
                .map(this::toCitation)
                .toList();
    }

    private RAGAnswerService.Citation toCitation(SearchService.Result hit) {
        String preview = sanitizeAndTruncate(hit.content());
        return new RAGAnswerService.Citation(hit.documentId(), hit.chunkIndex(), preview, hit.score());
    }

    private String sanitizeAndTruncate(String content) {
        if (content == null) return "";

        String sanitized = content.replaceAll("\\s+", " ").trim();
        int maxLength = ragProperties.getCitation().getPreviewLength();

        return sanitized.length() > maxLength
            ? sanitized.substring(0, maxLength) + "..."
            : sanitized;
    }
}
