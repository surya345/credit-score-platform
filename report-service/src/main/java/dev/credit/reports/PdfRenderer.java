package dev.credit.reports;

import dev.credit.platform.ApiException;
import java.io.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.springframework.stereotype.Component;

@Component
public class PdfRenderer {
  public byte[] render(ReportService.ReportView r) {
    try (var doc = new PDDocument();
        var regularStream = getClass().getResourceAsStream("/fonts/DejaVuSans.ttf");
        var boldStream = getClass().getResourceAsStream("/fonts/DejaVuSans-Bold.ttf");
        var bytes = new ByteArrayOutputStream()) {
      // Embed fonts so reports render consistently on machines without Helvetica.
      var regular = PDType0Font.load(doc, Objects.requireNonNull(regularStream));
      var bold = PDType0Font.load(doc, Objects.requireNonNull(boldStream));
      var page = new PDPage();
      doc.addPage(page);
      try (var stream = new PDPageContentStream(doc, page)) {
        stream.beginText();
        stream.setFont(bold, 21);
        stream.newLineAtOffset(50, 740);
        stream.showText("Credit analysis report");
        stream.setFont(regular, 10);
        stream.setLeading(20);
        List<String> lines =
            new ArrayList<>(
                List.of(
                    "",
                    "EDUCATIONAL MODEL - NOT A BUREAU CREDIT SCORE",
                    "",
                    "Report: " + r.id(),
                    "Customer: " + r.customerId(),
                    "Generated: " + r.generatedAt(),
                    "Source version: " + r.sourceVersion(),
                    "",
                    "Score: " + r.result().score() + " / 850",
                    "Demo risk band: " + r.result().riskBand(),
                    "Model: " + r.result().algorithmVersion(),
                    "",
                    "Base points: 300"));
        r.result().contributions().forEach((k, v) -> lines.add(k + ": " + v));
        lines.add("");
        lines.add("Model observations:");
        lines.addAll(r.result().observations());
        for (String line : lines) {
          stream.newLine();
          stream.showText(line.replaceAll("[^ -~]", "?"));
        }
        stream.endText();
      }
      doc.save(bytes);
      return bytes.toByteArray();
    } catch (IOException e) {
      throw new ApiException(500, "Unable to generate PDF.");
    }
  }
}
