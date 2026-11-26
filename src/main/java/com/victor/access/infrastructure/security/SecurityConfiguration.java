package com.victor.access.infrastructure.security;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, GatewayTokenIntrospector introspector,
                                            GatewayRateLimits limits, SecurityFailureMetrics failures) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/device-events").hasRole("DEVICE")
                        .requestMatchers(HttpMethod.POST, "/devices", "/devices/*/credentials",
                                "/credential-refs", "/devices/*/credential-refs").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/devices/*/credential-refs").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/devices/*", "/devices/*/events").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors.accessDeniedHandler(failures.forbidden()))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(failures.unauthorized())
                        .opaqueToken(opaque -> opaque.introspector(introspector)))
                .addFilterBefore(new OriginRateLimitFilter(limits), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new IdentityRateLimitFilter(limits), BearerTokenAuthenticationFilter.class)
                .build();
    }
}
