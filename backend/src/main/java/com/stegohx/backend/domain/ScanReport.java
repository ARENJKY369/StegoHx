package com.stegohx.backend.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** Persisted scan result (H2). Holds the analyzer verdict plus the raw
 *  analyzer JSON for report regeneration. */
@Entity
@Table(name = "scan_reports")
public class ScanReport {

    @Id
    private String id;

    private Instant createdAt;
    private String fileName;
    private long fileSizeBytes;
    private String mime;
    private String kind;
    private double threatScore;
    private double confidence;
    private String verdict;
    private String summary;
    private String recommendedAction;
    private String analyzerVersion;
    private String modelUsed;
    private int analysisMs;

    @Lob
    private String analyzersJson;

    public ScanReport() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public void setFileSizeBytes(long fileSizeBytes) {
        this.fileSizeBytes = fileSizeBytes;
    }

    public String getMime() {
        return mime;
    }

    public void setMime(String mime) {
        this.mime = mime;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public double getThreatScore() {
        return threatScore;
    }

    public void setThreatScore(double threatScore) {
        this.threatScore = threatScore;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getRecommendedAction() {
        return recommendedAction;
    }

    public void setRecommendedAction(String recommendedAction) {
        this.recommendedAction = recommendedAction;
    }

    public String getAnalyzerVersion() {
        return analyzerVersion;
    }

    public void setAnalyzerVersion(String analyzerVersion) {
        this.analyzerVersion = analyzerVersion;
    }

    public String getModelUsed() {
        return modelUsed;
    }

    public void setModelUsed(String modelUsed) {
        this.modelUsed = modelUsed;
    }

    public int getAnalysisMs() {
        return analysisMs;
    }

    public void setAnalysisMs(int analysisMs) {
        this.analysisMs = analysisMs;
    }

    public String getAnalyzersJson() {
        return analyzersJson;
    }

    public void setAnalyzersJson(String analyzersJson) {
        this.analyzersJson = analyzersJson;
    }
}
