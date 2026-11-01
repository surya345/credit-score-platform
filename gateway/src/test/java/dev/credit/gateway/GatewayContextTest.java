package dev.credit.gateway;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteLocator;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayContextTest {
  @Autowired
  @Qualifier("creditRoutes")
  RouteLocator routes;

  @Test
  void gatewayStartsAndRegistersAllRoutes() {
    assertThat(routes.getRoutes().collectList().block(Duration.ofSeconds(5))).hasSize(8);
  }
}
