package com.acme.security.autoconfigure;

import com.acme.security.KeycloakJwtAuthenticationConverter;
import com.acme.security.SecurityProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Configures a stateless OAuth2 JWT resource server with Keycloak realm-role mapping and method
 * security. Permits a configurable path list; everything else requires authentication. Fully
 * overridable — a consumer can define their own {@link SecurityFilterChain}.
 */
@AutoConfiguration
@ConditionalOnClass(SecurityFilterChain.class)
@EnableConfigurationProperties(SecurityProperties.class)
@EnableMethodSecurity
public class SecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public KeycloakJwtAuthenticationConverter keycloakJwtAuthenticationConverter() {
        return new KeycloakJwtAuthenticationConverter();
    }

    @Bean
    @ConditionalOnMissingBean
    public SecurityFilterChain acmeSecurityFilterChain(
            HttpSecurity http, KeycloakJwtAuthenticationConverter converter, SecurityProperties props)
            throws Exception {
        http.authorizeHttpRequests(
                        auth -> auth.requestMatchers(props.getPermitPaths().toArray(String[]::new))
                                .permitAll()
                                .anyRequest()
                                .authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
