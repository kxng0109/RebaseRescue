package io.github.kxng0109.rebaserescue.error;

/**
 * Thrown when the input guardrail blocks a diff (prompt-injection attempt or high-confidence secret). Maps to HTTP 400
 * — no LLM call was made.
 */
public class BlockedDiffException extends RuntimeException {

	public BlockedDiffException() {
		super("Diff blocked by input guardrail");
	}

	public BlockedDiffException(String message) {
		super(message);
	}

	public BlockedDiffException(String message, Throwable cause) {
		super(message, cause);
	}
}
