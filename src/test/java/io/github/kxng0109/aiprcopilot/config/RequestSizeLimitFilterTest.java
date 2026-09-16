package io.github.kxng0109.aiprcopilot.config;

import io.github.kxng0109.aiprcopilot.error.DiffTooLargeException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RequestSizeLimitFilterTest {

	private static final long MAX_BYTES = 100L;

	@Mock
	private PrCopilotAnalysisProperties analysisProperties;

	@Mock
	private GithubProperties githubProperties;

	@Mock
	private GithubProperties.Webhook webhookProperties;

	@Mock
	private ObjectProvider<GithubProperties> githubProvider;

	@Mock
	private FilterChain filterChain;

	private final ObjectMapper objectMapper = JsonMapper.builder().build();

	private RequestSizeLimitFilter filter;

	@BeforeEach
	void setup() {
		lenient().when(analysisProperties.getMaxRequestBytes()).thenReturn(MAX_BYTES);
		lenient().when(githubProvider.getIfAvailable()).thenReturn(githubProperties);
		lenient().when(githubProperties.getWebhook()).thenReturn(webhookProperties);
		lenient().when(webhookProperties.getMaxRequestBytes()).thenReturn(50L);
		filter = new RequestSizeLimitFilter(analysisProperties, githubProvider, objectMapper);
	}

	@Test
	void shouldReject413_whenContentLengthExceedsCap() throws Exception {
		MockHttpServletRequest request = apiRequest("x".repeat(101));
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, filterChain);

		verify(filterChain, never()).doFilter(any(), any());
		assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
		assertThat(response.getContentType()).contains(MediaType.APPLICATION_JSON_VALUE);
		assertThat(response.getContentAsString()).contains("\"statusCode\":413");
		assertThat(response.getContentAsString()).contains("/api/v1/analyze-diff");
	}

	@Test
	void shouldReject413_whenChunkedBodyExceedsCap() throws Exception {
		HttpServletRequest request = chunkedRequest("y".repeat(150));
		MockHttpServletResponse response = new MockHttpServletResponse();
		doAnswer(invocation -> {
			HttpServletRequest wrapped = invocation.getArgument(0);
			wrapped.getInputStream().readAllBytes();
			return null;
		}).when(filterChain).doFilter(any(), any());

		filter.doFilter(request, response, filterChain);

		verify(filterChain).doFilter(any(), any());
		assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
		assertThat(response.getContentAsString()).contains("\"statusCode\":413");
	}

	@Test
	void wrapper_shouldThrowDiffTooLarge_whenReadPastCap() throws Exception {
		HttpServletRequest request = chunkedRequest("w".repeat(120));
		MockHttpServletResponse response = new MockHttpServletResponse();
		ArgumentCaptor<HttpServletRequest> captor = ArgumentCaptor.forClass(HttpServletRequest.class);

		filter.doFilter(request, response, filterChain);

		verify(filterChain).doFilter(captor.capture(), any());
		HttpServletRequest wrapped = captor.getValue();
		assertThatThrownBy(() -> wrapped.getInputStream().readAllBytes())
				.isInstanceOf(DiffTooLargeException.class);
	}

	@Test
	void shouldPassThrough_whenBodyWithinCap() throws Exception {
		String payload = "{\"diff\":\"small\"}";
		MockHttpServletRequest request = apiRequest(payload);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, filterChain);

		ArgumentCaptor<HttpServletRequest> captor = ArgumentCaptor.forClass(HttpServletRequest.class);
		verify(filterChain).doFilter(captor.capture(), any());
		byte[] downstream = captor.getValue().getInputStream().readAllBytes();
		assertThat(new String(downstream, StandardCharsets.UTF_8)).isEqualTo(payload);
		assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
	}

	@Test
	void shouldPassThrough_whenBodyExactlyAtCap() throws Exception {
		MockHttpServletRequest request = apiRequest("z".repeat(100));
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, filterChain);

		verify(filterChain).doFilter(any(), any());
	}

	@Test
	void shouldSkip_whenMethodIsNotPost() throws Exception {
		MockHttpServletRequest request = apiRequest("x".repeat(500));
		request.setMethod("GET");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, filterChain);

		verify(filterChain).doFilter(any(), any());
	}

	@Test
	void shouldSkip_whenPathIsOutsideApi() throws Exception {
		MockHttpServletRequest request = apiRequest("x".repeat(500));
		request.setRequestURI("/actuator/health");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, filterChain);

		verify(filterChain).doFilter(any(), any());
	}

	@Test
	void shouldReturnWithoutWriting_whenCommittedFastPath() throws Exception {
		MockHttpServletRequest request = apiRequest("x".repeat(101));
		MockHttpServletResponse response = new MockHttpServletResponse();
		response.setCommitted(true);

		filter.doFilter(request, response, filterChain);

		verify(filterChain, never()).doFilter(any(), any());
		assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
	}

	@Test
	void shouldFilter_whenContextPathNull() throws Exception {
		HttpServletRequest request = chunkedRequest("y".repeat(150));
		when(request.getContextPath()).thenReturn(null);
		MockHttpServletResponse response = new MockHttpServletResponse();
		doAnswer(invocation -> {
			HttpServletRequest wrapped = invocation.getArgument(0);
			wrapped.getInputStream().readAllBytes();
			return null;
		}).when(filterChain).doFilter(any(), any());

		filter.doFilter(request, response, filterChain);

		assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
	}

	@Test
	void shouldRethrow_whenDownstreamFailsWithoutCapCause() throws Exception {
		HttpServletRequest request = chunkedRequest("y".repeat(150));
		MockHttpServletResponse response = new MockHttpServletResponse();
		RuntimeException failure = new RuntimeException("downstream blew up");
		doAnswer(invocation -> {
			throw failure;
		}).when(filterChain).doFilter(any(), any());

		assertThatThrownBy(() -> filter.doFilter(request, response, filterChain))
				.isSameAs(failure);
		assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
	}

	@Test
	void shouldReject413_whenCapCauseIsWrapped() throws Exception {
		HttpServletRequest request = chunkedRequest("y".repeat(150));
		MockHttpServletResponse response = new MockHttpServletResponse();
		doAnswer(invocation -> {
			HttpServletRequest wrapped = invocation.getArgument(0);
			try {
				wrapped.getInputStream().readAllBytes();
				return null;
			} catch (DiffTooLargeException e) {
				throw new IllegalStateException(e);
			}
		}).when(filterChain).doFilter(any(), any());

		filter.doFilter(request, response, filterChain);

		assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
	}

	@Test
	void shouldRethrow_whenResponseAlreadyCommitted() throws Exception {
		HttpServletRequest request = chunkedRequest("y".repeat(150));
		MockHttpServletResponse response = new MockHttpServletResponse();
		response.setCommitted(true);
		DiffTooLargeException failure = new DiffTooLargeException("late");
		doAnswer(invocation -> {
			throw failure;
		}).when(filterChain).doFilter(any(), any());

		assertThatThrownBy(() -> filter.doFilter(request, response, filterChain))
				.isSameAs(failure);
	}

	@Test
	void shouldStripContextPath_whenMatching() throws Exception {
		MockHttpServletRequest request = apiRequest("x".repeat(101));
		request.setContextPath("/app");
		request.setRequestURI("/app/api/v1/analyze-diff");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, filterChain);

		verify(filterChain, never()).doFilter(any(), any());
		assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
	}

	@Test
	void shouldFilter_whenContextPathDoesNotMatch() throws Exception {
		MockHttpServletRequest request = apiRequest("x".repeat(101));
		request.setContextPath("/other");
		request.setRequestURI("/api/v1/analyze-diff");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, filterChain);

		verify(filterChain, never()).doFilter(any(), any());
		assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
	}

	@Test
	void shouldEchoRequestIdHeader_whenPresent() throws Exception {
		HttpServletRequest request = chunkedRequest("y".repeat(150));
		when(request.getHeader(any())).thenReturn("corr-1");
		MockHttpServletResponse response = new MockHttpServletResponse();
		doAnswer(invocation -> {
			HttpServletRequest wrapped = invocation.getArgument(0);
			wrapped.getInputStream().readAllBytes();
			return null;
		}).when(filterChain).doFilter(any(), any());

		filter.doFilter(request, response, filterChain);

		assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
		assertThat(response.getContentAsString()).contains("\"requestId\":\"corr-1\"");
	}

	@Test
	void shouldOmitRequestId_whenHeaderBlank() throws Exception {
		HttpServletRequest request = chunkedRequest("y".repeat(150));
		when(request.getHeader(any())).thenReturn("  ");
		MockHttpServletResponse response = new MockHttpServletResponse();
		doAnswer(invocation -> {
			HttpServletRequest wrapped = invocation.getArgument(0);
			wrapped.getInputStream().readAllBytes();
			return null;
		}).when(filterChain).doFilter(any(), any());

		filter.doFilter(request, response, filterChain);

		assertThat(response.getContentAsString()).contains("\"requestId\":null");
	}

	@Test
	void wrapperReader_shouldDecodeWithFallbackCharset() throws Exception {
		HttpServletRequest request = chunkedRequest("hello");
		when(request.getCharacterEncoding()).thenReturn(null);
		MockHttpServletResponse response = new MockHttpServletResponse();
		ArgumentCaptor<HttpServletRequest> captor = ArgumentCaptor.forClass(HttpServletRequest.class);

		filter.doFilter(request, response, filterChain);

		verify(filterChain).doFilter(captor.capture(), any());
		HttpServletRequest wrapped = captor.getValue();
		assertThat(wrapped.getReader().readLine()).isEqualTo("hello");
		assertThat(wrapped.getReader()).isSameAs(wrapped.getReader());
		assertThat(wrapped.getInputStream()).isSameAs(wrapped.getInputStream());
	}

	@Test
	void wrapperReader_shouldHonorExplicitCharset() throws Exception {
		HttpServletRequest request = chunkedRequest("hello");
		when(request.getCharacterEncoding()).thenReturn("  ");
		MockHttpServletResponse response = new MockHttpServletResponse();
		ArgumentCaptor<HttpServletRequest> captor = ArgumentCaptor.forClass(HttpServletRequest.class);

		filter.doFilter(request, response, filterChain);

		verify(filterChain).doFilter(captor.capture(), any());
		assertThat(captor.getValue().getReader().readLine()).isEqualTo("hello");
	}

	@Test
	void wrapperReader_shouldDecodeNamedCharset() throws Exception {
		HttpServletRequest request = chunkedRequest("hello");
		when(request.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8.name());
		MockHttpServletResponse response = new MockHttpServletResponse();
		ArgumentCaptor<HttpServletRequest> captor = ArgumentCaptor.forClass(HttpServletRequest.class);

		filter.doFilter(request, response, filterChain);

		verify(filterChain).doFilter(captor.capture(), any());
		assertThat(captor.getValue().getReader().readLine()).isEqualTo("hello");
	}

	@Test
	void wrapperStream_shouldDelegateLifecycleMethods() throws Exception {
		HttpServletRequest request = chunkedRequest("hi");
		ServletInputStream backing = mock(ServletInputStream.class);
		when(backing.isFinished()).thenReturn(false);
		when(backing.isReady()).thenReturn(true);
		when(request.getInputStream()).thenReturn(backing);
		MockHttpServletResponse response = new MockHttpServletResponse();
		ArgumentCaptor<HttpServletRequest> captor = ArgumentCaptor.forClass(HttpServletRequest.class);

		filter.doFilter(request, response, filterChain);

		verify(filterChain).doFilter(captor.capture(), any());
		ServletInputStream stream = captor.getValue().getInputStream();
		assertThat(stream.isFinished()).isFalse();
		assertThat(stream.isReady()).isTrue();
		stream.setReadListener(mock(ReadListener.class));
		verify(backing).setReadListener(any(ReadListener.class));
	}

	@Test
	void wrapperStream_shouldSupportSingleByteReadsToEndOfStream() throws Exception {
		HttpServletRequest request = chunkedRequest("hi");
		MockHttpServletResponse response = new MockHttpServletResponse();
		ArgumentCaptor<HttpServletRequest> captor = ArgumentCaptor.forClass(HttpServletRequest.class);

		filter.doFilter(request, response, filterChain);

		verify(filterChain).doFilter(captor.capture(), any());
		ServletInputStream stream = captor.getValue().getInputStream();
		StringBuilder decoded = new StringBuilder();
		int b;
		while ((b = stream.read()) != -1) {
			decoded.append((char) b);
		}
		assertThat(decoded.toString()).isEqualTo("hi");
	}

	@Test
	void shouldReject413_whenWebhookContentLengthExceedsCap() throws Exception {
		MockHttpServletRequest request = webhookRequest("x".repeat(51));
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, filterChain);

		verify(filterChain, never()).doFilter(any(), any());
		assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
	}

	@Test
	void shouldReject413_whenWebhookChunkedBodyExceedsCap() throws Exception {
		HttpServletRequest request = webhookChunkedRequest("y".repeat(60));
		MockHttpServletResponse response = new MockHttpServletResponse();
		doAnswer(invocation -> {
			HttpServletRequest wrapped = invocation.getArgument(0);
			wrapped.getInputStream().readAllBytes();
			return null;
		}).when(filterChain).doFilter(any(), any());

		filter.doFilter(request, response, filterChain);

		assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
	}

	@Test
	void shouldPassThrough_whenWebhookBodyWithinCap() throws Exception {
		MockHttpServletRequest request = webhookRequest("{\"action\":\"opened\"}");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, filterChain);

		verify(filterChain).doFilter(any(), any());
		assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
	}

	@Test
	void shouldOmitRequestId_whenHeaderFailsPattern() throws Exception {
		HttpServletRequest request = chunkedRequest("y".repeat(150));
		when(request.getHeader(any())).thenReturn("bad\ninjection<script>");
		MockHttpServletResponse response = new MockHttpServletResponse();
		doAnswer(invocation -> {
			HttpServletRequest wrapped = invocation.getArgument(0);
			wrapped.getInputStream().readAllBytes();
			return null;
		}).when(filterChain).doFilter(any(), any());

		filter.doFilter(request, response, filterChain);

		assertThat(response.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE.value());
		assertThat(response.getContentAsString()).contains("\"requestId\":null");
		assertThat(response.getContentAsString()).doesNotContain("injection");
	}

	private static MockHttpServletRequest apiRequest(String body) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setMethod("POST");
		request.setRequestURI("/api/v1/analyze-diff");
		request.setContentType(MediaType.APPLICATION_JSON_VALUE);
		request.setContent(body.getBytes(StandardCharsets.UTF_8));
		return request;
	}

	private static HttpServletRequest chunkedRequest(String body) throws Exception {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getMethod()).thenReturn("POST");
		when(request.getRequestURI()).thenReturn("/api/v1/analyze-diff");
		lenient().when(request.getContextPath()).thenReturn("");
		when(request.getContentLengthLong()).thenReturn(-1L);
		lenient().when(request.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8.name());
		lenient().when(request.getHeader(any())).thenReturn(null);
		ServletInputStream backing =
				new DelegatingServletInputStream(new ByteArrayInputStream(bytes));
		lenient().when(request.getInputStream()).thenReturn(backing);
		return request;
	}

	private static MockHttpServletRequest webhookRequest(String body) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setMethod("POST");
		request.setRequestURI("/api/webhooks/github");
		request.setContentType(MediaType.APPLICATION_JSON_VALUE);
		request.setContent(body.getBytes(StandardCharsets.UTF_8));
		return request;
	}

	private static HttpServletRequest webhookChunkedRequest(String body) throws Exception {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getMethod()).thenReturn("POST");
		when(request.getRequestURI()).thenReturn("/api/webhooks/github");
		lenient().when(request.getContextPath()).thenReturn("");
		when(request.getContentLengthLong()).thenReturn(-1L);
		lenient().when(request.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8.name());
		lenient().when(request.getHeader(any())).thenReturn(null);
		ServletInputStream backing =
				new DelegatingServletInputStream(new ByteArrayInputStream(bytes));
		lenient().when(request.getInputStream()).thenReturn(backing);
		return request;
	}
}
