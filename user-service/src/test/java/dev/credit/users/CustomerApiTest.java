package dev.credit.users;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.credit.platform.AuditRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerApiTest {
  @Autowired MockMvc mvc;
  @Autowired CustomerRepository customers;
  @Autowired AuditRepository audit;
  String valid =
      """
      {"reference":"TEST-001","fullName":"Test Customer","email":"test@example.test","currency":"INR"}
      """;

  @BeforeEach
  void reset() {
    customers.deleteAll();
    audit.deleteAll();
  }

  @Test
  void anonymousIsUnauthorized() throws Exception {
    mvc.perform(get("/api/customers")).andExpect(status().isUnauthorized());
  }

  @Test
  void unrelatedRoleIsForbidden() throws Exception {
    mvc.perform(
            get("/api/customers")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void officerCreatesAndFindsCustomer() throws Exception {
    mvc.perform(
            post("/api/customers")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER")))
                .contentType("application/json")
                .content(valid))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    mvc.perform(
            get("/api/customers?search=test")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));
  }

  @Test
  void invalidEmailIsRejected() throws Exception {
    mvc.perform(
            post("/api/customers")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER")))
                .contentType("application/json")
                .content(valid.replace("test@example.test", "bad")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void duplicateReferenceIsConflict() throws Exception {
    mvc.perform(
            post("/api/customers")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER")))
                .contentType("application/json")
                .content(valid))
        .andExpect(status().isCreated());
    mvc.perform(
            post("/api/customers")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER")))
                .contentType("application/json")
                .content(valid))
        .andExpect(status().isConflict());
  }

  @Test
  void auditRequiresAdministrator() throws Exception {
    mvc.perform(
            get("/api/admin/audit")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OFFICER"))))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/admin/audit")
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
  }
}
