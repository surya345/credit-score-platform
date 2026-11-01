package dev.credit.users;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(name = "app.demo-seed", havingValue = "true")
public class DemoCustomers {
  @Bean
  ApplicationRunner seed(CustomerRepository r, CustomerService s) {
    return args -> {
      if (r.count() == 0) {
        s.create(
            new CustomerRequest("DEMO-001", "Aarav Sharma", "aarav@example.test", "INR"),
            "demo-seed");
        s.create(
            new CustomerRequest("DEMO-002", "Maya Lind", "maya@example.test", "SEK"), "demo-seed");
        s.create(
            new CustomerRequest("DEMO-003", "Sofia Andersson", "sofia@example.test", "SEK"),
            "demo-seed");
      }
    };
  }
}
