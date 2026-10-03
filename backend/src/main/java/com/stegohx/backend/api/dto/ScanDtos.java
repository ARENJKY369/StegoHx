package com.stegohx.backend.api.dto;

import java.time.Instant;

import com.stegohx.backend.api.dto.AnalyzerDtos.AnalysisResponse;

/** Engine-level scan DTOs. */
public final class ScanDtos {

    private ScanDtos() {
    }

    /**
     * @param scanId        engine-side scan identifier
     * @param createdAt     scan timestamp
     * @param engineVersion engine version that produced the scan
     * @param analysis      the analyzer service's full assessment (pass-through)
     */
    public record ScanResponse(String scanId, Instant createdAt, String engineVersion,
            AnalysisResponse analysis) {
    }
}
