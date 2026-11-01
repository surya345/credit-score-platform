package dev.credit.reports;

import dev.credit.platform.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.mail.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
  private final ReportRepository reports;
  private final ReportService service;
  private final PdfRenderer pdf;
  private final MailSender mail;
  private final AuditRepository audit;

  public ReportController(
      ReportRepository r, ReportService s, PdfRenderer p, MailSender m, AuditRepository a) {
    reports = r;
    service = s;
    pdf = p;
    mail = m;
    audit = a;
  }

  @GetMapping("/customers/{id}")
  public Map<String, Object> list(
      @PathVariable UUID id, @RequestParam(defaultValue = "0") int page) {
    if (page < 0) throw new ApiException(400, "Invalid page.");
    var r =
        reports.findByCustomerIdOrderBySourceVersionDesc(id.toString(), PageRequest.of(page, 20));
    return Map.of(
        "items",
        r.getContent().stream().map(service::view).toList(),
        "total",
        r.getTotalElements(),
        "page",
        page);
  }

  @GetMapping("/{id}")
  public ReportService.ReportView get(@PathVariable UUID id) {
    return service.view(service.get(id.toString()));
  }

  @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
  public ResponseEntity<byte[]> download(@PathVariable UUID id) {
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=credit-report-" + id + ".pdf")
        .cacheControl(CacheControl.noStore())
        .body(pdf.render(get(id)));
  }

  public record EmailRequest(@NotBlank @Email @Size(max = 200) String to) {}

  @PostMapping("/{id}/email")
  public Map<String, String> email(
      @PathVariable UUID id,
      @Valid @RequestBody EmailRequest req,
      @AuthenticationPrincipal Jwt jwt) {
    var r = get(id);
    var m = new SimpleMailMessage();
    m.setFrom("reports@example.test");
    m.setTo(req.to());
    m.setSubject("Educational credit report " + id);
    m.setText(
        "Educational demo only. Customer "
            + r.customerId()
            + " scored "
            + r.result().score()
            + " / 850. Demo risk band: "
            + r.result().riskBand()
            + ". Not a bureau score or lending recommendation.");
    try {
      mail.send(m);
    } catch (MailException e) {
      throw new ApiException(503, "Email delivery failed.");
    }
    audit.save(new AuditEntry(jwt.getSubject(), "REPORT_EMAIL_SENT", id.toString()));
    return Map.of("status", "sent");
  }
}
