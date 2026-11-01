package dev.credit.gateway;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import reactor.core.publisher.Flux;

@Configuration
public class GatewaySecurity {
  @Bean
  ReactiveJwtDecoder decoder(
      @Value("${app.security.issuer}") String issuer,
      @Value("${app.security.jwk-set-uri}") String jwks) {
    var d = NimbusReactiveJwtDecoder.withJwkSetUri(jwks).build();
    OAuth2TokenValidator<Jwt> a =
        j ->
            j.getAudience().contains("credit-api")
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Incorrect audience", null));
    d.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), a));
    return d;
  }

  @Bean
  SecurityWebFilterChain security(ServerHttpSecurity http) {
    var c = new ReactiveJwtAuthenticationConverter();
    c.setJwtGrantedAuthoritiesConverter(
        jwt -> {
          Map<String, Object> realm = jwt.getClaimAsMap("realm_access");
          Object roles = realm == null ? null : realm.get("roles");
          if (!(roles instanceof Collection<?> values)) return Flux.empty();
          return Flux.fromIterable(values)
              .map(Object::toString)
              .filter(r -> Set.of("OFFICER", "ADMIN").contains(r))
              .map(r -> new SimpleGrantedAuthority("ROLE_" + r));
        });
    return http.csrf(x -> x.disable())
        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        .authorizeExchange(
            a ->
                a.pathMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .pathMatchers("/api/admin/**")
                    .hasRole("ADMIN")
                    .pathMatchers("/api/**")
                    .hasAnyRole("OFFICER", "ADMIN")
                    .anyExchange()
                    .denyAll())
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(c)))
        .build();
  }
}
