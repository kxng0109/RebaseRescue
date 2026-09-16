package io.github.kxng0109.rebaserescue.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Pre-shared-key authentication for {@code selfhost} mode.
 *
 * <p>Accepts {@code Authorization: Bearer <key>} or the raw key value in the
 * configured header. Uses constant-time comparison. Missing/invalid keys leave the request unauthenticated so the
 * filter chain returns 401/403.
 *
 * <p>The key is held as UTF-8 bytes (never as a retained {@code String}) to narrow
 * secret lifetime in memory. Note the framework-owned properties bean still holds the source string; this filter
 * minimizes its own retention only.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

	private final byte[] expectedKey;
	private final String headerName;

	public ApiKeyAuthFilter(String configuredKey, String headerName) {
		String key = configuredKey == null ? "" : configuredKey;
		this.expectedKey = key.getBytes(StandardCharsets.UTF_8);
		this.headerName = headerName == null || headerName.isBlank() ? "Authorization" : headerName;
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		if (expectedKey.length == 0) {
			filterChain.doFilter(request, response);
			return;
		}

		String header = request.getHeader(headerName);
		String presented = header == null
				? null
				: (header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7).trim() : header.trim());

		if (presented != null
				&& MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expectedKey)) {
			var auth = new UsernamePasswordAuthenticationToken(
					"selfhost", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
			SecurityContextHolder.getContext().setAuthentication(auth);
		}

		filterChain.doFilter(request, response);
	}
}
