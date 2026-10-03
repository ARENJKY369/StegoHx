package com.stegohx.backend.repo;

import java.util.List;

import com.stegohx.backend.domain.ScanReport;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScanReportRepository extends JpaRepository<ScanReport, String> {

    List<ScanReport> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
