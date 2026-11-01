package dev.credit.data;

import dev.credit.platform.ApiException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public class CustomerClient {
  private final RestClient client;

  public CustomerClient(@Value("${app.users-url}") String url) {
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  public record CustomerSummary(String id, String currency, String status) {}

  public CustomerSummary verify(String id, String token) {
    try {
      return client
          .get()
          .uri("/api/customers/{id}", id)
          .headers(h -> h.setBearerAuth(token))
          .retrieve()
          .body(CustomerSummary.class);
    } catch (HttpClientErrorException.NotFound e) {
      throw new ApiException(404, "Customer not found.");
    } catch (HttpClientErrorException.Unauthorized e) {
      throw new ApiException(401, "Session expired.");
    } catch (HttpClientErrorException.Forbidden e) {
      throw new ApiException(403, "Access denied.");
    } catch (RestClientException e) {
      throw new ApiException(503, "Customer service is unavailable.");
    }
  }
}
