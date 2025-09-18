package com.example.demo.controller;

import com.example.demo.service.EmbeddingJobService;
import com.example.demo.service.RAGAnswerService;
import com.example.demo.service.RagIngestService;
import com.example.demo.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
class RagController {

    private final RagIngestService ingest;
    private final EmbeddingJobService jobs;
    private final SearchService search;
    private final RAGAnswerService service;

    @PostMapping("/ingest")
    public Map<String, Object> ingest(@RequestParam MultipartFile file) throws IOException {
        UUID docId = ingest.ingestTextOnly(file);
        return Map.of("documentId", docId);
    }

    @PostMapping("/embed/{docId}")
    public Map<String, Object> embed(@PathVariable UUID docId) {
        jobs.embedDocument(docId);
        return Map.of("ok", true, "documentId", docId);
    }

    @GetMapping("/search/hybrid")
    public List<SearchService.Result> hybrid(
            @RequestParam("q") String q,
            @RequestParam(value = "k", defaultValue = "8") int k,
            @RequestParam(value = "alpha", defaultValue = "0.7") double alpha,
            @RequestParam(value = "perDoc", defaultValue = "2") int perDoc
    ) {
        return search.hybridSearch(q, k, alpha, perDoc);
    }

    @PostMapping("/answer")
    public RAGAnswerService.AnswerResponse answer(@RequestBody AnswerRequest req) throws IOException {
        return service.answer(req.question(), req.k(), req.alpha(), req.perDoc());
    }

    public record AnswerRequest(String question, Integer k, Double alpha, Integer perDoc) {}

}