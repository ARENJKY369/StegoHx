package com.stegohx.backend.service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stegohx.backend.api.dto.AnalyzerDtos.AnalysisResponse;
import com.stegohx.backend.api.dto.AnalyzerDtos.AnalyzerFinding;
import com.stegohx.backend.api.dto.AnalyzerDtos.FileMeta;
import com.stegohx.backend.api.dto.ScanDtos.ScanResponse;
import com.stegohx.backend.domain.ScanReport;
import com.stegohx.backend.repo.ScanReportRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Runs analyses through the analyzer client and persists the results.
 */
@Service
public class ScanService {

    public static final String ENGINE_VERSION = "1.0.0";

    private final AnalyzerClient analyzerClient;
    private final ScanReportRepository repository;
    private final ObjectMapper objectMapper;

    public ScanService(AnalyzerClient analyzerClient, ScanReportRepository repository, ObjectMapper objectMapper) {
        this.analyzerClient = analyzerClient;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public ScanResponse scanAndPersist(byte[] data, String filename) {
        AnalysisResponse analysis = analyzerClient.analyze(data, filename);
        ScanReport report = new ScanReport();
        report.setId(UUID.randomUUID().toString());
        report.setCreatedAt(Instant.now());
        report.setFileName(analysis.file().name());
        report.setFileSizeBytes(analysis.file().sizeBytes());
        report.setMime(analysis.file().mime());
        report.setKind(analysis.file().kind());
        report.setThreatScore(analysis.threatScore());
        report.setConfidence(analysis.confidence());
        report.setVerdict(analysis.verdict());
        report.setSummary(analysis.summary());
        report.setRecommendedAction(analysis.recommendedAction());
        report.setAnalyzerVersion(analysis.analyzerVersion());
        report.setModelUsed(analysis.modelUsed());
        report.setAnalysisMs(analysis.analysisMs());
        try {
            report.setAnalyzersJson(objectMapper.writeValueAsString(analysis.analyzers()));
        } catch (Exception e) {
            report.setAnalyzersJson("[]");
        }
        repository.save(report);
        return new ScanResponse(report.getId(), report.getCreatedAt(), ENGINE_VERSION, analysis);
    }

    public List<ScanResponse> recentScans(int limit) {
        int bounded = Math.max(1, Math.min(50, limit));
        Pageable pageable = PageRequest.of(0, bounded);
        return repository.findAllByOrderByCreatedAtDesc(pageable).stream()
                .map(r -> toResponse(r, false))
                .toList();
    }

    /** Full scan (with analyzer detail); null when the id is unknown. */
    public ScanResponse byId(String id) {
        return repository.findById(id).map(r -> toResponse(r, true)).orElse(null);
    }

    /** Raw entity for the report generator; null when the id is unknown. */
    public ScanReport entityById(String id) {
        return repository.findById(id).orElse(null);
    }

    public long count() {
        return repository.count();
    }

    private ScanResponse toResponse(ScanReport report, boolean withDetail) {
        List<AnalyzerFinding> findings = withDetail ? parseFindings(report.getAnalyzersJson())
                : Collections.emptyList();
        AnalysisResponse analysis = new AnalysisResponse(
                report.getAnalyzerVersion(),
                new FileMeta(report.getFileName(), report.getFileSizeBytes(), report.getMime(),
                        report.getKind(), null, null, null, null),
                report.getThreatScore(),
                report.getConfidence(),
                report.getVerdict(),
                report.getSummary(),
                report.getRecommendedAction(),
                findings,
                report.getModelUsed(),
                report.getAnalysisMs());
        return new ScanResponse(report.getId(), report.getCreatedAt(), ENGINE_VERSION, analysis);
    }

    private List<AnalyzerFinding> parseFindings(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<AnalyzerFinding>>() {
            });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
