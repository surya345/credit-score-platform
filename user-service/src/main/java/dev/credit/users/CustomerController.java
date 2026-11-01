package dev.credit.users;

import dev.credit.platform.ApiException;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CustomerController {
  private final CustomerService service;
  private final CustomerRepository customers;

  public CustomerController(CustomerService s, CustomerRepository c) {
    service = s;
    customers = c;
  }

  @GetMapping("/customers")
  public Map<String, Object> list(
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    if (page < 0 || size < 1 || size > 100 || search.length() > 120)
      throw new ApiException(400, "Invalid search or pagination.");
    var result =
        customers.findByFullNameContainingIgnoreCaseOrReferenceContainingIgnoreCase(
            search.trim(),
            search.trim(),
            PageRequest.of(page, size, Sort.by("createdAt").descending()));
    return Map.of(
        "items",
        result.getContent(),
        "total",
        result.getTotalElements(),
        "page",
        page,
        "size",
        size);
  }

  @GetMapping("/customers/{id}")
  public Customer get(@PathVariable String id) {
    return service.get(id);
  }

  @PostMapping("/customers")
  @ResponseStatus(HttpStatus.CREATED)
  public Customer create(@Valid @RequestBody CustomerRequest r, @AuthenticationPrincipal Jwt jwt) {
    return service.create(r, jwt.getSubject());
  }

  @PutMapping("/customers/{id}")
  public Customer update(
      @PathVariable String id,
      @Valid @RequestBody CustomerRequest r,
      @AuthenticationPrincipal Jwt jwt) {
    return service.update(id, r, jwt.getSubject());
  }

  @GetMapping("/users/me")
  public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
    return Map.of(
        "id",
        jwt.getSubject(),
        "username",
        jwt.getClaimAsString("preferred_username"),
        "roles",
        jwt.getClaimAsMap("realm_access"));
  }
}
