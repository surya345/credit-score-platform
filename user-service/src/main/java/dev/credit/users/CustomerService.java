package dev.credit.users;

import dev.credit.platform.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {
  private final CustomerRepository customers;
  private final AuditRepository audit;

  public CustomerService(CustomerRepository c, AuditRepository a) {
    customers = c;
    audit = a;
  }

  public Customer get(String id) {
    return customers.findById(id).orElseThrow(() -> new ApiException(404, "Customer not found."));
  }

  @Transactional
  public Customer create(CustomerRequest r, String actor) {
    var c = new Customer();
    c.id = UUID.randomUUID().toString();
    c.createdAt = Instant.now();
    c.status = "ACTIVE";
    apply(c, r);
    customers.save(c);
    audit.save(new AuditEntry(actor, "CUSTOMER_CREATED", c.id));
    return c;
  }

  @Transactional
  public Customer update(String id, CustomerRequest r, String actor) {
    var c = get(id);
    apply(c, r);
    audit.save(new AuditEntry(actor, "CUSTOMER_UPDATED", id));
    return c;
  }

  private void apply(Customer c, CustomerRequest r) {
    c.reference = r.reference().trim().toUpperCase(Locale.ROOT);
    c.fullName = r.fullName().trim();
    c.email = r.email().trim().toLowerCase(Locale.ROOT);
    c.currency = r.currency();
  }
}
