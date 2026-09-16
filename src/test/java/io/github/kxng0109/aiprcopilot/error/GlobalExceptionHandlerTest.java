package io.github.kxng0109.aiprcopilot.error;

import io.github.kxng0109.aiprcopilot.api.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	@Mock
	private NoResourceFoundException noResourceFoundException;

	@Mock
	private HttpRequestMethodNotSupportedException methodNotSupportedException;

	@Mock
	private HttpMessageNotReadableException notReadableException;

	@Mock
	private MethodArgumentNotValidException validationException;

	@Mock
	private BeanPropertyBindingResult bindingResult;

	private static MockHttpServletRequest request(String requestIdHeader) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRequestURI("/api/v1/analyze-diff");
		if (requestIdHeader != null) {
			request.addHeader(ErrorResponse.REQUEST_ID_HEADER, requestIdHeader);
		}
		return request;
	}

	@Test
	void handleDiffTooLarge_shouldReturn413() {
		ResponseEntity<ErrorResponse> response = handler.handleDiffTooLargeException(
				new DiffTooLargeException("too big"), request(null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
		assertThat(response.getBody().message()).isEqualTo("too big");
		assertThat(response.getBody().path()).isEqualTo("/api/v1/analyze-diff");
	}

	@Test
	void handleBlocked_shouldReturn400() {
		ResponseEntity<ErrorResponse> response =
				handler.handleBlockedDiffException(new BlockedDiffException("nope"), request(null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody().message()).isEqualTo("nope");
	}

	@Test
	void handleNoResourceFound_shouldReturn404() {
		when(noResourceFoundException.getMessage()).thenReturn("No static resource missing.");
		ResponseEntity<ErrorResponse> response =
				handler.handleNoResourceFound(noResourceFoundException, request(null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void handleValidation_shouldReturnFieldMap() {
		when(validationException.getBindingResult()).thenReturn(bindingResult);
		when(bindingResult.getFieldErrors()).thenReturn(List.of(
				new FieldError("req", "diff", "must not be blank"),
				new FieldError("req", "requestId", "bad format")
		));
		ResponseEntity<ErrorResponse> response =
				handler.handleMethodArgumentNotValidException(validationException, request(null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody().message()).isEqualTo("Validation failed");
		assertThat(response.getBody().validationErrors())
				.containsEntry("diff", "must not be blank")
				.containsEntry("requestId", "bad format");
	}

	@Test
	void handleMethodNotSupported_shouldReturn405() {
		when(methodNotSupportedException.getMessage())
				.thenReturn("Request method 'GET' is not supported.");
		ResponseEntity<ErrorResponse> response = handler
				.handleHttpRequestMethodNotSupportedException(methodNotSupportedException, request(null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
	}

	@Test
	void handleNotReadable_shouldRewriteMissingBody() {
		when(notReadableException.getMessage())
				.thenReturn("Required request body is missing: some details");
		ResponseEntity<ErrorResponse> response = handler
				.handleHttpMessageNotReadableException(notReadableException, request(null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody().message())
				.isEqualTo("Request body is missing. JSON object required.");
	}

	@Test
	void handleNotReadable_shouldUseGenericMessage() {
		when(notReadableException.getMessage()).thenReturn("JSON parse error at line 1");
		ResponseEntity<ErrorResponse> response = handler
				.handleHttpMessageNotReadableException(notReadableException, request(null));

		assertThat(response.getBody().message()).isEqualTo("Malformed JSON request body.");
	}

	@Test
	void handleNotReadable_shouldUseGenericMessage_whenMessageNull() {
		when(notReadableException.getMessage()).thenReturn(null);
		ResponseEntity<ErrorResponse> response = handler
				.handleHttpMessageNotReadableException(notReadableException, request(null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody().message()).isEqualTo("Malformed JSON request body.");
	}

	@Test
	void handleModelOutputParse_shouldReturn422() {
		ResponseEntity<ErrorResponse> response = handler.handleModelOutputParseException(
				new ModelOutputParseException("bad json with preview secrets"), request(null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
		assertThat(response.getBody().message()).isEqualTo("AI model returned unparsable output.");
	}

	@Test
	void handleCustomApi_shouldPassThroughStatus() {
		ResponseEntity<ErrorResponse> response = handler.handleCustomApiException(
				new CustomApiException("slow down", HttpStatus.TOO_MANY_REQUESTS), request(null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(response.getBody().message()).isEqualTo("slow down");
	}

	@Test
	void handleCustomApi_shouldFallBackTo500_whenStatusNull() {
		ResponseEntity<ErrorResponse> response = handler.handleCustomApiException(
				new CustomApiException("mystery", null), request(null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}

	@Test
	void handleCustomApi_shouldSanitize5xx() {
		ResponseEntity<ErrorResponse> response = handler.handleCustomApiException(
				new CustomApiException("Failed to resolve internal-ai-7: Name or service not known",
						HttpStatus.BAD_GATEWAY),
				request(null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
		assertThat(response.getBody().message()).isEqualTo("Bad Gateway");
		assertThat(response.getBody().message()).doesNotContain("internal-ai-7");
	}

	@Test
	void handleGeneric_shouldReturn500WithMessage() {
		ResponseEntity<ErrorResponse> response =
				handler.handleException(new RuntimeException("boom"), request(null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(response.getBody().message()).isEqualTo("Internal Server Error");
		assertThat(response.getBody().message()).doesNotContain("boom");
	}

	@Test
	void handleGeneric_shouldUseReasonPhrase_whenMessageNull() {
		ResponseEntity<ErrorResponse> response =
				handler.handleException(new RuntimeException(), request(null));

		assertThat(response.getBody().message()).isEqualTo("Internal Server Error");
	}

	@Test
	void handleGeneric_shouldUseReasonPhrase_whenMessageBlank() {
		ResponseEntity<ErrorResponse> response =
				handler.handleException(new RuntimeException("   "), request(null));

		assertThat(response.getBody().message()).isEqualTo("Internal Server Error");
	}

	@Test
	void build_shouldEchoValidRequestIdHeader() {
		ResponseEntity<ErrorResponse> response =
				handler.handleException(new RuntimeException("x"), request("corr-123"));

		assertThat(response.getBody().requestId()).isEqualTo("corr-123");
	}

	@Test
	void build_shouldDropInvalidRequestIdHeader() {
		ResponseEntity<ErrorResponse> response =
				handler.handleException(new RuntimeException("x"), request("evil\ninjection"));

		assertThat(response.getBody().requestId()).isNull();
	}
}
