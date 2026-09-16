package io.github.kxng0109.rebaserescue.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class SecurityConfigUnitTest {

	private static PrCopilotAuthProperties props(AuthMode mode, String apiKey) {
		PrCopilotAuthProperties properties = new PrCopilotAuthProperties();
		properties.setMode(mode);
		properties.setApiKey(apiKey);
		return properties;
	}

	@Test
	void securityFilterChain_shouldThrow_whenProdWithoutIssuer() {
		SecurityConfig config = new SecurityConfig(props(AuthMode.PROD, ""));
		HttpSecurity http = mock(HttpSecurity.class, RETURNS_DEEP_STUBS);

		assertThatThrownBy(() -> config.securityFilterChain(http))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("issuer");
	}

	@Test
	void securityFilterChain_shouldBuild_whenProdWithIssuer() throws Exception {
		SecurityConfig config =
				new SecurityConfig(props(AuthMode.PROD, ""));
		ReflectionTestUtils.setField(config, "issuerUri", "https://auth.example.com/realms/x");
		HttpSecurity http = mock(HttpSecurity.class, RETURNS_DEEP_STUBS);

		config.securityFilterChain(http);
	}

	@Test
	void securityFilterChain_shouldThrow_whenModeNull() {
		SecurityConfig config = new SecurityConfig(props(null, "k"));
		HttpSecurity http = mock(HttpSecurity.class, RETURNS_DEEP_STUBS);

		assertThatThrownBy(() -> config.securityFilterChain(http))
				.isInstanceOf(NullPointerException.class)
				.hasMessageContaining("prcopilot.auth.mode");
	}

	@Test
	void securityFilterChain_shouldThrow_whenProdIssuerNull() {
		SecurityConfig config = new SecurityConfig(props(AuthMode.PROD, ""));
		ReflectionTestUtils.setField(config, "issuerUri", null);
		HttpSecurity http = mock(HttpSecurity.class, RETURNS_DEEP_STUBS);

		assertThatThrownBy(() -> config.securityFilterChain(http))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("issuer");
	}

	@Test
	void securityFilterChain_shouldThrow_whenProdIssuerBlank() {
		SecurityConfig config = new SecurityConfig(props(AuthMode.PROD, ""));
		ReflectionTestUtils.setField(config, "issuerUri", "  ");
		HttpSecurity http = mock(HttpSecurity.class, RETURNS_DEEP_STUBS);

		assertThatThrownBy(() -> config.securityFilterChain(http))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("issuer");
	}

	@Test
	void securityFilterChain_shouldWarn_whenSelfhostKeyBlank() throws Exception {
		SecurityConfig config = new SecurityConfig(props(AuthMode.SELFHOST, ""));
		HttpSecurity http = mock(HttpSecurity.class, RETURNS_DEEP_STUBS);

		config.securityFilterChain(http);
	}

	@Test
	void securityFilterChain_shouldWarn_whenSelfhostKeyNull() throws Exception {
		PrCopilotAuthProperties properties = props(AuthMode.SELFHOST, "");
		properties.setApiKey(null);
		SecurityConfig config = new SecurityConfig(properties);
		HttpSecurity http = mock(HttpSecurity.class, RETURNS_DEEP_STUBS);

		config.securityFilterChain(http);
	}

	@Test
	void corsConfigurationSource_shouldDenyByDefault() {
		var source = new SecurityConfig(props(AuthMode.SELFHOST, "k")).corsConfigurationSource();

		assertThat(source).isNotNull();
		CorsConfiguration configuration =
				source.getCorsConfigurations().get("/api/v1/**");
		assertThat(configuration).isNotNull();
		assertThat(configuration.getAllowedOrigins()).isNullOrEmpty();
		assertThat(configuration.getAllowedOriginPatterns()).isNullOrEmpty();
	}
}
