package io.github.kxng0109.rebaserescue.error;

/**
 * Exception thrown when an error occurs while parsing the JSON output from the model.
 *
 * <p>Indicates that the model's output was invalid or could not be processed as expected.
 */
public class ModelOutputParseException extends RuntimeException {
	/**
	 * Exception thrown when an error occurs while parsing the JSON output from the model.
	 */
	public ModelOutputParseException(String message) {
		super(message);
	}

	/**
	 * Exception thrown when an error occurs while parsing the JSON output from the model.
	 */
	public ModelOutputParseException() {
		super("Error occurred while parsing JSON from the model.");
	}
}
