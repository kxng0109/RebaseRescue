package io.github.kxng0109.rebaserescue.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthFilterTest {

	private static final String KEY = "secret-key-123";

	@Mock
	private FilterChain filterChain;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	private MockHttpServletRequest requestWith(String headerValue) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		if (headerValue != null) {
			request.addHeader("Authorization", headerValue);
		}
		return request;
	}

	private Authentication authenticate(String configuredKey, String headerValue) throws Exception {
		ApiKeyAuthFilter filter = new ApiKeyAuthFilter(configuredKey, "Authorization");
		MockHttpServletRequest request = requestWith(headerValue);
		filter.doFilter(request, new MockHttpServletResponse(), filterChain);
		verify(filterChain).doFilter(any(), any());
		return SecurityContextHolder.getContext().getAuthentication();
	}

	@Test
	void shouldAuthenticate_whenBearerKeyMatches() throws Exception {
		Authentication auth = authenticate(KEY, "Bearer " + KEY);

		assertThat(auth).isNotNull();
		assertThat(auth.getAuthorities()).extracting(Object::toString).contains("ROLE_USER");
	}

	@Test
	void shouldAuthenticate_whenRawKeyMatches() throws Exception {
		assertThat(authenticate(KEY, KEY)).isNotNull();
	}

	@Test
	void shouldAuthenticate_whenBearerPrefixCaseDiffersAndPadded() throws Exception {
		assertThat(authenticate(KEY, "bearer   " + KEY + "  ")).isNotNull();
	}

	@Test
	void shouldNotAuthenticate_whenKeyIsWrong() throws Exception {
		assertThat(authenticate(KEY, "Bearer wrong")).isNull();
	}

	@Test
	void shouldNotAuthenticate_whenHeaderMissing() throws Exception {
		assertThat(authenticate(KEY, null)).isNull();
	}

	@Test
	void shouldPassThroughWithoutAuth_whenConfiguredKeyIsEmpty() throws Exception {
		assertThat(authenticate("", "Bearer anything")).isNull();
	}

	@Test
	void shouldPassThroughWithoutAuth_whenConfiguredKeyIsNull() throws Exception {
		assertThat(authenticate(null, "Bearer anything")).isNull();
	}

	@Test
	void shouldDefaultHeader_whenHeaderNameNull() throws Exception {
		ApiKeyAuthFilter filter = new ApiKeyAuthFilter(KEY, null);
		MockHttpServletRequest request = requestWith("Bearer " + KEY);
		filter.doFilter(request, new MockHttpServletResponse(), filterChain);

		verify(filterChain).doFilter(any(), any());
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
	}

	@Test
	void shouldDefaultHeader_whenHeaderNameBlank() throws Exception {
		ApiKeyAuthFilter filter = new ApiKeyAuthFilter(KEY, "  ");
		MockHttpServletRequest request = requestWith("Bearer " + KEY);
		filter.doFilter(request, new MockHttpServletResponse(), filterChain);

		verify(filterChain).doFilter(any(), any());
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
	}
}
