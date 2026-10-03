package com.stegohx.backend.api.controller;

import java.util.List;

import com.stegohx.backend.api.dto.ScanDtos.ScanResponse;
import com.stegohx.backend.core.StegoException;
import com.stegohx.backend.domain.ScanReport;
import com.stegohx.backend.service.ReportService;
import com.stegohx.backend.service.ScanService;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Scan, history and report endpoints. */
@RestController
@RequestMapping("/api/v1")
public class ScanController {

    private final ScanService scanService;
    private final ReportService reportService;

    public ScanController(ScanService scanService, ReportService reportService) {
        this.scanService = scanService;
        this.reportService = reportService;
    }

    @PostMapping(value = "/scan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ScanResponse scan(@RequestPart("file") MultipartFile file) throws Exception {
        if (file.isEmpty()) {
            throw new StegoException("empty upload");
        }
        return scanService.scanAndPersist(file.getBytes(), file.getOriginalFilename());
    }

    @GetMapping("/scans")
    public List<ScanResponse> scans(@RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        return scanService.recentScans(limit);
    }

    @GetMapping("/scans/{id}")
    public ResponseEntity<ScanResponse> scanById(@PathVariable String id) {
        ScanResponse response = scanService.byId(id);
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }

    @GetMapping("/scans/{id}/report.pdf")
    public ResponseEntity<byte[]> report(@PathVariable String id) {
        ScanReport entity = scanService.entityById(id);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        ScanResponse scan = scanService.byId(id);
        byte[] pdf = reportService.renderScanReport(scan);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"stegohx-report-" + id + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
