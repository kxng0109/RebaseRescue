package io.github.kxng0109.rebaserescue.config;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Authentication mode for the service.
 *
 * <ul>
 *   <li>{@code prod} — OIDC resource server required (fail-closed, no default issuer).</li>
 *   <li>{@code selfhost} — single-user API key, no external IdP.</li>
 * </ul>
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "prcopilot.auth")
public class PrCopilotAuthProperties {

	/**
	 * Auth mode: {@code SELFHOST} or {@code PROD} (bound case-insensitively).
	 */
	@NotNull(message = "Auth mode must not be null")
	private AuthMode mode = AuthMode.SELFHOST;

	/**
	 * Pre-shared API key for {@code selfhost} mode. Empty means API endpoints stay locked.
	 */
	private String apiKey = "";

	/**
	 * Header carrying the key (default {@code Authorization}, {@code Bearer <key>} accepted).
	 */
	private String apiKeyHeader = "Authorization";
}
