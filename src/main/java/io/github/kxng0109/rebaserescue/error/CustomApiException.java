package io.github.kxng0109.rebaserescue.error;

import org.springframework.http.HttpStatus;

/**
 * Exception indicating an error related to a custom API operation.
 * <p>
 * Encapsulates an HTTP status code alongside a descriptive error message.
 */
public class CustomApiException extends RuntimeException {
	private final HttpStatus httpStatus;

	/**
	 * Constructs a {@code CustomApiException} with the specified detail message and HTTP status.
	 *
	 * @param message    the detail message, must not be {@code null} or empty
	 * @param httpStatus the HTTP status associated with the exception, must not be {@code null}
	 */
	public CustomApiException(String message, HttpStatus httpStatus) {
		super(message);
		this.httpStatus = httpStatus;
	}

	/**
	 * Constructs a {@code CustomApiException} with the specified detail message, HTTP status, and root cause.
	 *
	 * @param message    the detail message, must not be {@code null} or empty
	 * @param httpStatus the HTTP status associated with the exception, must not be {@code null}
	 * @param cause      the root cause of the exception, may be {@code null}
	 */
	public CustomApiException(String message, HttpStatus httpStatus, Throwable cause) {
		super(message, cause);
		this.httpStatus = httpStatus;
	}

	/**
	 * Returns the HTTP status associated with this exception.
	 *
	 * @return the HTTP status, never {@code null}
	 */
	public HttpStatus getHttpStatus() {
		return httpStatus;
	}
}
