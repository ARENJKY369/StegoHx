package com.stegohx.backend.service;

import java.awt.Color;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.stegohx.backend.api.dto.AnalyzerDtos.AnalyzerFinding;
import com.stegohx.backend.api.dto.ScanDtos.ScanResponse;
import com.stegohx.backend.core.StegoException;

import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;

/**
 * Renders scan results as a professional PDF report (OpenPDF / LibrePDF).
 */
@Service
public class ReportService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
            .withZone(ZoneOffset.UTC);

    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20);
    private static final Font H2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 10);
    private static final Font MONO = FontFactory.getFont(FontFactory.COURIER, 8.5f);
    private static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.GRAY);

    public byte[] renderScanReport(ScanResponse scan) {
        try {
            Document doc = new Document(PageSize.A4, 48, 48, 48, 48);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfWriter.getInstance(doc, out);
            doc.open();

            doc.add(new Paragraph("StegoHX Security Report", TITLE));
            doc.add(new Paragraph("Steganographic media analysis - generated " + TS.format(scan.createdAt()), SMALL));
            doc.add(spacer(10));

            // --- verdict banner -------------------------------------------------
            Color verdictColor = colorFor(scan.analysis().verdict());
            PdfPTable banner = new PdfPTable(1);
            banner.setWidthPercentage(100);
            PdfPCell verdictCell = new PdfPCell(new Phrase(String.format("%s  |  threat score %.3f  |  confidence %.3f",
                    scan.analysis().verdict(), scan.analysis().threatScore(), scan.analysis().confidence()),
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, Color.WHITE)));
            verdictCell.setBackgroundColor(verdictColor);
            verdictCell.setPadding(10f);
            verdictCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            banner.addCell(verdictCell);
            doc.add(banner);
            doc.add(spacer(12));

            // --- file metadata --------------------------------------------------
            doc.add(new Paragraph("Analyzed file", H2));
            doc.add(spacer(4));
            PdfPTable meta = new PdfPTable(2);
            meta.setWidthPercentage(100);
            meta.setWidths(new float[]{35f, 65f});
            row(meta, "File name", scan.analysis().file().name());
            row(meta, "Size", scan.analysis().file().sizeBytes() + " bytes");
            row(meta, "MIME type", scan.analysis().file().mime());
            row(meta, "Media kind", scan.analysis().file().kind());
            row(meta, "Analyzer version", scan.analysis().analyzerVersion() + " (model: "
                    + scan.analysis().modelUsed() + ")");
            row(meta, "Analysis time", scan.analysis().analysisMs() + " ms");
            row(meta, "Scan id", scan.scanId());
            doc.add(meta);
            doc.add(spacer(12));

            // --- summary --------------------------------------------------------
            doc.add(new Paragraph("Summary", H2));
            doc.add(spacer(4));
            doc.add(new Paragraph(scan.analysis().summary(), BODY));
            doc.add(spacer(12));

            // --- analyzer findings ----------------------------------------------
            doc.add(new Paragraph("Analyzer findings", H2));
            doc.add(spacer(4));
            List<AnalyzerFinding> findings = scan.analysis().analyzers() == null ? List.of()
                    : scan.analysis().analyzers();
            if (findings.isEmpty()) {
                doc.add(new Paragraph("No analyzer detail stored for this scan.", BODY));
            } else {
                PdfPTable table = new PdfPTable(4);
                table.setWidthPercentage(100);
                table.setWidths(new float[]{26f, 12f, 12f, 50f});
                table.addCell(cell("Analyzer", headerCell()));
                table.addCell(cell("Score", headerCell()));
                table.addCell(cell("Weight", headerCell()));
                table.addCell(cell("Notes", headerCell()));
                for (AnalyzerFinding f : findings) {
                    table.addCell(cell(f.displayName() + (f.applicable() ? "" : " (n/a)"), bodyCell()));
                    table.addCell(cell(String.format("%.3f", f.score()),
                            scoreCell(f.score(), f.applicable())));
                    table.addCell(cell(String.format("%.2f", f.weight()), bodyCell()));
                    table.addCell(cell(f.notes() == null ? "" : f.notes(), bodyCell()));
                }
                doc.add(table);
            }
            doc.add(spacer(12));

            // --- footer ----------------------------------------------------------
            doc.add(new Paragraph(
                    "Scope: this report was produced by StegoHX for authorized security testing or research "
                            + "on media the operator is entitled to analyze. Statistical steganalysis is "
                            + "probabilistic; INCONCLUSIVE results must not be treated as proof of embedding.",
                    SMALL));
            doc.close();
            return out.toByteArray();
        } catch (StegoException e) {
            throw e;
        } catch (Exception e) {
            throw new StegoException("report generation failed: " + e.getMessage(), e);
        }
    }

    // --- helpers -------------------------------------------------------------

    private static Paragraph spacer(float height) {
        Paragraph p = new Paragraph(" ");
        p.setLeading(height);
        return p;
    }

    private static void row(PdfPTable table, String key, String value) {
        table.addCell(cell(key, headerCell()));
        table.addCell(cell(value == null ? "-" : value, bodyCell()));
    }

    private static PdfPCell cell(String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text == null || text.isBlank() ? "-" : text, font));
        c.setPadding(6f);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return c;
    }

    private static Font headerCell() {
        return FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, new Color(30, 41, 59));
    }

    private static Font bodyCell() {
        return FontFactory.getFont(FontFactory.HELVETICA, 9.5f);
    }

    private static Font scoreCell(double score, boolean applicable) {
        if (!applicable) {
            return FontFactory.getFont(FontFactory.HELVETICA, 9.5f, Color.GRAY);
        }
        Color color = score >= 0.7 ? new Color(185, 28, 28)
                : score >= 0.45 ? new Color(180, 83, 9)
                        : score >= 0.20 ? new Color(133, 100, 4) : new Color(21, 128, 61);
        return FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, color);
    }

    private static Color colorFor(String verdict) {
        if (verdict == null) {
            return Color.GRAY;
        }
        return switch (verdict) {
            case "HIGH_CONFIDENCE_STEGO" -> new Color(153, 27, 27);
            case "LIKELY_STEGO" -> new Color(194, 65, 12);
            case "INCONCLUSIVE" -> new Color(161, 98, 7);
            default -> new Color(21, 128, 61);
        };
    }
}
