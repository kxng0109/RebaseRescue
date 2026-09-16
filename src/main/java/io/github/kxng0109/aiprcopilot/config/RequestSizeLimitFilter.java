package io.github.kxng0109.aiprcopilot.config;

import io.github.kxng0109.aiprcopilot.api.dto.ErrorResponse;
import io.github.kxng0109.aiprcopilot.error.DiffTooLargeException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Byte-level request body cap for {@code /api/v1/**} JSON endpoints and the
 * GitHub webhook at {@code /api/webhooks/github}.
 *
 * <p>Tomcat's {@code max-swallow-size}/{@code max-http-form-post-size} and Spring's
 * {@code spring.servlet.multipart.max-request-size} do not apply to JSON bodies, so without this filter a
 * multi-megabyte payload would be fully deserialized by Jackson before the character-based diff-size check runs. This
 * filter rejects oversized bodies before deserialization:
 *
 * <ol>
 *   <li>Fast path: when {@code Content-Length} is present and exceeds
 *       the applicable cap, respond 413 immediately without
 *       reading the body.</li>
 *   <li>Chunked/unknown length: wrap the input stream with a counting stream that throws
 *       {@link DiffTooLargeException} once the cap is exceeded, which the MVC exception
 *       handler maps to 413.</li>
 * </ol>
 *
 * <p>Runs at {@link Ordered#HIGHEST_PRECEDENCE} so oversized bodies are rejected before
 * authentication and deserialization work begins. The 413 body matches the
 * {@code ErrorResponse} shape. Request IDs are echoed only when they match
 * the shared correlation pattern.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

	private static final String API_PREFIX = "/api/v1/";

	private static final String WEBHOOK_PREFIX = "/api/webhooks/github";

	private final PrCopilotAnalysisProperties analysisProperties;

	private final ObjectProvider<GithubProperties> githubPropertiesProvider;

	private final ObjectMapper objectMapper;

	public RequestSizeLimitFilter(PrCopilotAnalysisProperties analysisProperties,
			ObjectProvider<GithubProperties> githubPropertiesProvider, ObjectMapper objectMapper) {
		this.analysisProperties = analysisProperties;
		this.githubPropertiesProvider = githubPropertiesProvider;
		this.objectMapper = objectMapper;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		if (!"POST".equalsIgnoreCase(request.getMethod())) {
			return true;
		}
		String path = effectivePath(request);
		if (path.startsWith(API_PREFIX)) {
			return false;
		}
		return !isWebhookPath(path);
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		long maxBytes = resolveCap(request);

		if (request.getContentLengthLong() > maxBytes) {
			writePayloadTooLarge(request, response);
			return;
		}

		try {
			filterChain.doFilter(new CappedRequestWrapper(request, maxBytes), response);
		} catch (Exception ex) {
			if (!response.isCommitted() && causedByCapExceeded(ex)) {
				writePayloadTooLarge(request, response);
				return;
			}
			throw ex;
		}
	}

	private static boolean isWebhookPath(String path) {
		return path.equals(WEBHOOK_PREFIX) || path.startsWith(WEBHOOK_PREFIX + "/");
	}

	private long resolveCap(HttpServletRequest request) {
		if (isWebhookPath(effectivePath(request))) {
			GithubProperties githubProperties = githubPropertiesProvider.getIfAvailable();
			if (githubProperties != null && githubProperties.getWebhook() != null) {
				return githubProperties.getWebhook().getMaxRequestBytes();
			}
		}
		return analysisProperties.getMaxRequestBytes();
	}

	private static String effectivePath(HttpServletRequest request) {
		String uri = request.getRequestURI();
		String context = request.getContextPath();
		if (context != null && !context.isEmpty() && uri.startsWith(context)) {
			return uri.substring(context.length());
		}
		return uri;
	}

	private static boolean causedByCapExceeded(Throwable ex) {
		Throwable current = ex;
		while (current != null) {
			if (current instanceof DiffTooLargeException) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private void writePayloadTooLarge(HttpServletRequest request, HttpServletResponse response) throws IOException {
		if (response.isCommitted()) {
			return;
		}
		response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("timestamp", OffsetDateTime.now().toString());
		body.put("statusCode", HttpStatus.CONTENT_TOO_LARGE.value());
		body.put("error", HttpStatus.CONTENT_TOO_LARGE.getReasonPhrase());
		body.put("message", "Request body exceeded maximum allowed size");
		body.put("path", request.getRequestURI());
		String requestId = request.getHeader(ErrorResponse.REQUEST_ID_HEADER);
		if (requestId != null && requestId.matches(ErrorResponse.REQUEST_ID_PATTERN)) {
			body.put("requestId", requestId);
		} else {
			body.put("requestId", null);
		}

		response.getWriter().write(objectMapper.writeValueAsString(body));
	}

	/**
	 * Request wrapper that counts bytes read from the body and throws {@link DiffTooLargeException} once the cap is
	 * exceeded.
	 */
	private static final class CappedRequestWrapper extends HttpServletRequestWrapper {

		private final long maxBytes;

		private ServletInputStream cappedStream;

		private BufferedReader cappedReader;

		private CappedRequestWrapper(HttpServletRequest request, long maxBytes) {
			super(request);
			this.maxBytes = maxBytes;
		}

		@Override
		public ServletInputStream getInputStream() throws IOException {
			if (cappedStream == null) {
				cappedStream = new CappedServletInputStream(super.getInputStream(), maxBytes);
			}
			return cappedStream;
		}

		@Override
		public BufferedReader getReader() throws IOException {
			if (cappedReader == null) {
				String encoding = getCharacterEncoding();
				Charset charset = encoding == null || encoding.isBlank()
						? StandardCharsets.ISO_8859_1
						: Charset.forName(encoding);
				cappedReader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
			}
			return cappedReader;
		}
	}

	/**
	 * Counting stream decorator. Byte counting happens on every read path so neither Jackson nor any other reader can
	 * bypass the cap.
	 */
	private static final class CappedServletInputStream extends ServletInputStream {

		private final ServletInputStream delegate;

		private final long maxBytes;

		private long bytesRead;

		private CappedServletInputStream(ServletInputStream delegate, long maxBytes) {
			this.delegate = delegate;
			this.maxBytes = maxBytes;
		}

		@Override
		public int read() throws IOException {
			int b = delegate.read();
			if (b != -1) {
				count(1);
			}
			return b;
		}

		@Override
		public int read(byte[] buf, int off, int len) throws IOException {
			int n = delegate.read(buf, off, len);
			if (n > 0) {
				count(n);
			}
			return n;
		}

		private void count(int n) {
			bytesRead += n;
			if (bytesRead > maxBytes) {
				throw new DiffTooLargeException("Request body exceeded maximum allowed size");
			}
		}

		@Override
		public boolean isFinished() {
			return delegate.isFinished();
		}

		@Override
		public boolean isReady() {
			return delegate.isReady();
		}

		@Override
		public void setReadListener(ReadListener readListener) {
			delegate.setReadListener(readListener);
		}
	}
}
