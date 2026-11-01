package dev.credit.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.*;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.*;

@Configuration
public class Routes {
  @Bean
  RouteLocator creditRoutes(
      RouteLocatorBuilder b,
      @Value("${app.users-url}") String users,
      @Value("${app.data-url}") String data,
      @Value("${app.scores-url}") String scores,
      @Value("${app.reports-url}") String reports) {
    return b.routes()
        .route(
            "user-audit",
            r ->
                r.path("/api/admin/users/audit")
                    .filters(f -> f.setPath("/api/admin/audit"))
                    .uri(users))
        .route(
            "data-audit",
            r ->
                r.path("/api/admin/data/audit")
                    .filters(f -> f.setPath("/api/admin/audit"))
                    .uri(data))
        .route(
            "score-audit",
            r ->
                r.path("/api/admin/scores/audit")
                    .filters(f -> f.setPath("/api/admin/audit"))
                    .uri(scores))
        .route(
            "report-audit",
            r ->
                r.path("/api/admin/reports/audit")
                    .filters(f -> f.setPath("/api/admin/audit"))
                    .uri(reports))
        .route(
            "customers",
            r -> r.path("/api/customers", "/api/customers/**", "/api/users/**").uri(users))
        .route("data", r -> r.path("/api/data/**").uri(data))
        .route("scores", r -> r.path("/api/scores/**").uri(scores))
        .route("reports", r -> r.path("/api/reports/**").uri(reports))
        .build();
  }
}
