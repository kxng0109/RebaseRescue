package io.github.kxng0109.rebaserescue.api.dto;

import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Represents an error response from a service.
 *
 * @param timestamp        the timestamp when the error occurred, must not be null
 * @param statusCode       the HTTP status code associated with the error, must not be negative
 * @param error            a brief description of the type of error that occurred, must not be blank
 * @param message          a detailed message describing what went wrong, may be null
 * @param path             the URL path where the error occurred, may be null
 * @param requestId        a unique identifier for the request that led to the error, may be null
 * @param validationErrors field-level validation failures, may be null when not a validation error
 */
@Builder
public record ErrorResponse(
		OffsetDateTime timestamp,
		int statusCode,
		String error,
		String message,
		String path,
		String requestId,
		Map<String, String> validationErrors
) {

	/**
	 * Request header carrying the client-supplied correlation ID. Echoed back as {@code requestId} only when it matches
	 * {@link #REQUEST_ID_PATTERN}.
	 */
	public static final String REQUEST_ID_HEADER = "X-Request-ID";

	/**
	 * Allowed request-ID shape: 1-64 chars of letters, digits, {@code -} or {@code _}. The dash is last so it is
	 * literal, not a range. Rejects newlines and control characters to prevent log/response injection.
	 */
	public static final String REQUEST_ID_PATTERN = "[A-Za-z0-9_-]{1,64}";
}
