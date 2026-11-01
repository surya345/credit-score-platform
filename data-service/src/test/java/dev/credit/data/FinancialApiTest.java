package dev.credit.data;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.credit.platform.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FinancialApiTest {
  @Autowired MockMvc mvc;
  @Autowired FinancialRepository records;
  @Autowired OutboxRepository outbox;
  @Autowired AuditRepository audit;
  @MockitoBean CustomerClient customers;
  String customer = UUID.randomUUID().toString();
  String key;
  String valid =
      """
      {"monthlyIncome":80000,"monthlyDebtPayments":12000,"creditUtilizationPercent":25,"missedPaymentsLast12Months":0,"creditHistoryMonths":72,"recentCreditInquiries":1,"currency":"INR"}
      """;

  @BeforeEach
  void reset() {
    records.deleteAll();
    outbox.deleteAll();
    audit.deleteAll();
    key = UUID.randomUUID().toString();
    when(customers.verify(anyString(), anyString()))
        .thenReturn(new CustomerClient.CustomerSummary(customer, "INR", "ACTIVE"));
  }

  private org.springframework.test.web.servlet.ResultActions send(String body) throws Exception {
    return mvc.perform(
        post("/api/data/customers/" + customer)
            .with(
                jwt()
                    .jwt(j -> j.subject("officer"))
                    .authorities(new SimpleGrantedAuthority("ROLE_OFFICER")))
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .content(body));
  }

  @Test
  void retryCreatesOneRecordAndOneOutboxEntry() throws Exception {
    send(valid).andExpect(status().isAccepted()).andExpect(jsonPath("$.eventId").value(key));
    send(valid).andExpect(status().isAccepted()).andExpect(jsonPath("$.eventId").value(key));
    assertThat(records.count()).isEqualTo(1);
    assertThat(outbox.count()).isEqualTo(1);
  }

  @Test
  void reusedKeyWithChangedPayloadIsConflict() throws Exception {
    send(valid).andExpect(status().isAccepted());
    send(valid.replace("80000", "90000")).andExpect(status().isConflict());
    assertThat(records.count()).isEqualTo(1);
  }

  @Test
  void zeroIncomeCannotCreateEvent() throws Exception {
    send(valid.replace("80000", "0")).andExpect(status().isBadRequest());
    assertThat(records.count()).isZero();
    assertThat(outbox.count()).isZero();
  }

  @Test
  void fractionalPaymentCountIsRejected() throws Exception {
    send(valid.replace("\"missedPaymentsLast12Months\":0", "\"missedPaymentsLast12Months\":0.5"))
        .andExpect(status().isBadRequest());
    assertThat(outbox.count()).isZero();
  }

  @Test
  void missingCustomerCannotCreateEvent() throws Exception {
    when(customers.verify(anyString(), anyString()))
        .thenThrow(new ApiException(404, "Customer not found."));
    send(valid).andExpect(status().isNotFound());
    assertThat(records.count()).isZero();
    assertThat(outbox.count()).isZero();
  }
}
