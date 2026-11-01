package dev.credit.platform;

import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuditController {
  private final AuditRepository audit;

  public AuditController(AuditRepository a) {
    audit = a;
  }

  @GetMapping("/api/admin/audit")
  public Map<String, Object> list(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    if (page < 0 || size < 1 || size > 100) throw new ApiException(400, "Invalid pagination.");
    var result = audit.findAllByOrderByOccurredAtDesc(PageRequest.of(page, size));
    return Map.of(
        "content",
        result.getContent(),
        "totalElements",
        result.getTotalElements(),
        "page",
        page,
        "size",
        size);
  }
}
