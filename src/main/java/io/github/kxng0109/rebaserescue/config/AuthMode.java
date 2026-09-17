package io.github.kxng0109.rebaserescue.config;

/**
 * Authentication mode for the service.
 *
 * <p>Bound case-insensitively from {@code prcopilot.auth.mode}; any other value
 * fails startup binding instead of silently falling through to self-host.
 */
public enum AuthMode {

	/**
	 * Single-user pre-shared API key, no external IdP.
	 */
	SELFHOST,

	/**
	 * OIDC resource server required (fail-closed, no default issuer).
	 */
	PROD
}
