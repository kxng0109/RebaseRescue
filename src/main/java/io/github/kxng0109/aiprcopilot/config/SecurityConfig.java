package io.github.kxng0109.aiprcopilot.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Objects;

/**
 * Deny-by-default security.
 *
 * <ul>
 *   <li>Public: {@code /actuator/health}, {@code /actuator/info}, {@code /api-docs/**},
 *       {@code /swagger-ui/**}.</li>
 *   <li>{@code prod} mode: {@code /api/v1/**} requires OIDC JWT; startup fails closed
 *       when no issuer is configured.</li>
 *   <li>{@code selfhost} mode: {@code /api/v1/**} requires the pre-shared API key;
 *       empty key keeps endpoints locked (401).</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@Slf4j
@RequiredArgsConstructor
public class SecurityConfig {

	private final PrCopilotAuthProperties authProperties;

	@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}")
	private String issuerUri;

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		AuthMode mode = Objects.requireNonNull(
				authProperties.getMode(), "prcopilot.auth.mode must not be null");

		http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
		http.csrf(AbstractHttpConfigurer::disable);
		http.cors(Customizer.withDefaults());

		http.authorizeHttpRequests(auth -> auth.requestMatchers(
				                                       "/actuator/health",
				                                       "/actuator/info",
				                                       "/api-docs/**",
				                                       "/v3/api-docs/**",
				                                       "/swagger-ui/**",
				                                       "/swagger-ui.html",
				                                       "/api/webhooks/github"
		                                       )
		                                       .permitAll()
		                                       .requestMatchers(HttpMethod.POST, "/api/v1/**")
		                                       .authenticated()
		                                       .anyRequest()
		                                       .denyAll());

		if (mode == AuthMode.PROD) {
			if (issuerUri == null || issuerUri.isBlank()) {
				throw new IllegalStateException(
						"prcopilot.auth.mode=prod requires spring.security.oauth2.resourceserver.jwt.issuer-uri");
			}
			http.oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
			log.info("Auth mode=prod (OIDC JWT required for /api/v1/**)");
		} else {
			if (authProperties.getApiKey() == null || authProperties.getApiKey().isBlank()) {
				log.warn("Auth mode=selfhost with empty PRCOPILOT_API_KEY: /api/v1/** stays locked (401)");
			}
			http.addFilterBefore(
					new ApiKeyAuthFilter(authProperties.getApiKey(), authProperties.getApiKeyHeader()),
					UsernamePasswordAuthenticationFilter.class
			);
			log.info("Auth mode=selfhost (pre-shared API key for /api/v1/**)");
		}

		return http.build();
	}

	/**
	 * Explicit deny-by-default CORS configuration.
	 *
	 * <p>This is a same-origin / API-key service with no browser client, so no origins,
	 * methods, or headers are permitted. An empty {@link CorsConfiguration} denies all cross-origin requests (no
	 * {@code Access-Control-Allow-Origin} is ever emitted). Declared explicitly so auditors can see the deny posture
	 * instead of inferring it from the absence of configuration. To allow a dashboard origin in the future, set allowed
	 * origins/methods on this bean — never {@code *} with credentials.
	 *
	 * @return the CORS configuration source, never {@code null}
	 */
	@Bean
	UrlBasedCorsConfigurationSource corsConfigurationSource() {
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/v1/**", new CorsConfiguration());
		return source;
	}
}
