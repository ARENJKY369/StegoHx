package com.stegohx.backend.api.dto;

import java.util.List;
import java.util.Map;

/**
 * DTOs mirroring the Python analyzer service's JSON contract. Field names
 * are camelCase here and serialized snake_case via the global Jackson
 * strategy, matching the analyzer wire format exactly.
 */
public final class AnalyzerDtos {

    private AnalyzerDtos() {
    }

    public record AnalysisResponse(
            String analyzerVersion,
            FileMeta file,
            double threatScore,
            double confidence,
            String verdict,
            String summary,
            String recommendedAction,
            List<AnalyzerFinding> analyzers,
            String modelUsed,
            int analysisMs) {
    }

    public record FileMeta(
            String name,
            long sizeBytes,
            String mime,
            String kind,
            Integer width,
            Integer height,
            Integer durationSamples,
            Integer sampleRate) {
    }

    public record AnalyzerFinding(
            String name,
            String displayName,
            boolean applicable,
            double score,
            double weight,
            String notes,
            Map<String, Object> details) {
    }

    public record AnalyzerHealth(String status, String version, boolean modelLoaded, String modelPath) {
    }
}
