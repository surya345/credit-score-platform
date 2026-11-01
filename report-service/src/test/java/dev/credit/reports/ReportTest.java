package dev.credit.reports;

import static org.assertj.core.api.Assertions.*;

import dev.credit.contracts.*;
import dev.credit.platform.*;
import java.time.Instant;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ReportTest {
  @Autowired ReportService service;
  @Autowired ReportRepository reports;
  @Autowired AuditRepository audit;
  @Autowired PdfRenderer renderer;

  @BeforeEach
  void reset() {
    reports.deleteAll();
    audit.deleteAll();
  }

  private ScoreCalculatedEvent event() {
    String id = UUID.randomUUID().toString();
    return new ScoreCalculatedEvent(
        id,
        1,
        id,
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        1,
        Instant.now(),
        new ScoreResult(
            763,
            "LOW",
            "educational-v1",
            Map.of("Payment history", 200),
            List.of("Example observation.")));
  }

  @Test
  void duplicateDeliveryCreatesOneReport() {
    var e = event();
    service.process(e);
    service.process(e);
    assertThat(reports.count()).isEqualTo(1);
    assertThat(audit.count()).isEqualTo(1);
  }

  @Test
  void pdfIncludesScoreModelAndDisclaimer() throws Exception {
    service.process(event());
    byte[] pdf = renderer.render(service.view(reports.findAll().getFirst()));
    String artifact = System.getProperty("credit.test.pdf-output");
    if (artifact != null) java.nio.file.Files.write(java.nio.file.Path.of(artifact), pdf);
    try (var document = Loader.loadPDF(pdf)) {
      assertThat(document.getNumberOfPages()).isEqualTo(1);
      String text = new PDFTextStripper().getText(document);
      assertThat(text).contains("763", "educational-v1", "EDUCATIONAL MODEL");
    }
  }
}
