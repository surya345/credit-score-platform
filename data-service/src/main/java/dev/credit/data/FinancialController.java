package dev.credit.data;

import dev.credit.contracts.*;
import dev.credit.platform.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/data/customers/{customerId}")
public class FinancialController {
  private final FinancialService service;
  private final FinancialRepository records;
  private final CustomerClient customers;

  public FinancialController(FinancialService s, FinancialRepository r, CustomerClient c) {
    service = s;
    records = r;
    customers = c;
  }

  @PostMapping
  public ResponseEntity<FinancialDataEvent> submit(
      @PathVariable UUID customerId,
      @Valid @RequestBody FinancialInput input,
      @RequestHeader("Idempotency-Key") UUID key,
      @AuthenticationPrincipal Jwt jwt) {
    var c = customers.verify(customerId.toString(), jwt.getTokenValue());
    if (c == null || !"ACTIVE".equals(c.status()))
      throw new ApiException(409, "Customer is not active.");
    if (!input.currency().equals(c.currency()))
      throw new ApiException(400, "Currency must match the customer profile.");
    return ResponseEntity.accepted()
        .body(service.submit(customerId.toString(), input, jwt.getSubject(), key.toString()));
  }

  @GetMapping
  public Map<String, Object> history(
      @PathVariable UUID customerId, @RequestParam(defaultValue = "0") int page) {
    if (page < 0) throw new ApiException(400, "Invalid page.");
    var result =
        records.findByCustomerIdOrderByIdDesc(customerId.toString(), PageRequest.of(page, 20));
    return Map.of(
        "items",
        result.getContent().stream().map(service::view).toList(),
        "total",
        result.getTotalElements(),
        "page",
        page);
  }
}
