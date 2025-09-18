package com.example.demo.dto;

import java.util.UUID;

public record ReconstructedDocument(
    UUID documentId,
    String title,
    String sourceUri,
    String mimeType,
    String content,
    int   chunkCount,
    int   tokenEstimate
) {}
