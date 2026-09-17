package io.github.kxng0109.rebaserescue.error;

/**
 * Exception thrown when the provided diff content exceeds the maximum allowable size.
 */
public class DiffTooLargeException extends RuntimeException {
	/**
	 * Constructs a {@code DiffTooLargeException} with the specified detail message.
	 *
	 * @param message the detail message, must not be {@code null} or empty
	 */
	public DiffTooLargeException(String message) {
		super(message);
	}

	/**
	 * Constructs a {@code DiffTooLargeException} with a default detail message.
	 *
	 * <p>Indicates that the diff content size has exceeded the maximum allowable limit.
	 */
	public DiffTooLargeException() {
		super("Diff exceeded maximum allowed size");
	}
}
