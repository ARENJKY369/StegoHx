package com.stegohx.backend.api.controller;

import java.lang.management.ManagementFactory;
import java.util.List;

import com.stegohx.backend.api.dto.AnalyzerDtos.AnalyzerHealth;
import com.stegohx.backend.api.dto.StegoDtos.HealthStatus;
import com.stegohx.backend.api.dto.StegoDtos.ModuleInfo;
import com.stegohx.backend.api.dto.StegoDtos.SystemStatus;
import com.stegohx.backend.config.AppProperties;
import com.stegohx.backend.core.stego.StegoModuleRegistry;
import com.stegohx.backend.service.AnalyzerClient;
import com.stegohx.backend.service.ScanService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * System status, health and capability discovery. Everything this endpoint
 * reports is accurate and complete - StegoHX has no hidden functionality.
 */
@RestController
@RequestMapping("/api/v1")
public class SystemController {

    private final StegoModuleRegistry registry;
    private final AnalyzerClient analyzerClient;
    private final ScanService scanService;
    private final AppProperties properties;
    private final long startedAt = System.currentTimeMillis();

    public SystemController(StegoModuleRegistry registry, AnalyzerClient analyzerClient,
            ScanService scanService, AppProperties properties) {
        this.registry = registry;
        this.analyzerClient = analyzerClient;
        this.scanService = scanService;
        this.properties = properties;
    }

    @GetMapping("/system/status")
    public SystemStatus status() {
        AnalyzerHealth health = analyzerClient.health();
        List<ModuleInfo> modules = registry.all().stream()
                .map(m -> new ModuleInfo(m.id(), m.displayName(), m.description(),
                        m.mediaKind().name(), m.requiresCover()))
                .toList();
        boolean analyzerReachable = health != null;
        return new SystemStatus(
                analyzerReachable ? "OK" : "DEGRADED",
                new SystemStatus.EngineInfo(ScanService.ENGINE_VERSION,
                        System.getProperty("java.version", "unknown"),
                        (System.currentTimeMillis() - startedAt) / 1000),
                new SystemStatus.AnalyzerInfo(analyzerReachable,
                        properties.analyzer().baseUrl(),
                        analyzerReachable ? health.version() : null,
                        analyzerReachable && health.modelLoaded()),
                scanService.count(),
                modules);
    }

    @GetMapping("/health")
    public HealthStatus health() {
        return new HealthStatus("ok");
    }

    @GetMapping("/modules")
    public List<ModuleInfo> modules() {
        return registry.all().stream()
                .map(m -> new ModuleInfo(m.id(), m.displayName(), m.description(),
                        m.mediaKind().name(), m.requiresCover()))
                .toList();
    }
}
