package com.stegohx.backend.api.dto;

import java.util.List;

/** DTOs for stego operations. */
public final class StegoDtos {

    private StegoDtos() {
    }

    public record ModuleInfo(String id, String displayName, String description, String mediaKind,
            boolean requiresCover) {
    }

    public record HideResponse(String moduleId, String moduleName, String outputName, String outputMime,
            String outputBase64, long payloadBytes, double capacityUsedRatio) {
    }

    public record ExtractResponse(String moduleId, String moduleName, String payloadText,
            String payloadBase64, long payloadBytes, boolean containerVerified) {
    }

    public record CleanResponse(String moduleId, String moduleName, String outputMime,
            String outputBase64, String note) {
    }

    public record SystemStatus(String status, EngineInfo engine, AnalyzerInfo analyzer, long scansExecuted,
            List<ModuleInfo> modules) {

        public record EngineInfo(String version, String javaVersion, long uptimeSeconds) {
        }

        public record AnalyzerInfo(boolean reachable, String baseUrl, String version, boolean modelLoaded) {
        }
    }

    public record HealthStatus(String status) {
    }
}
