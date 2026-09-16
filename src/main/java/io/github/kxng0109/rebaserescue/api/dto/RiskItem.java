package io.github.kxng0109.rebaserescue.api.dto;

/**
 * A single identified risk with a deterministic severity level.
 *
 * @param level   one of {@code note}, {@code warning}, {@code error} (lowercase)
 * @param message description of the risk, must not be blank
 */
public record RiskItem(
		String level,
		String message
) {
	/**
	 * Normalizes model-provided severities onto the contract levels.
	 *
	 * @param raw raw level string; may be {@code null}
	 * @return {@code error}, {@code warning}, or {@code note}
	 * @throws IllegalArgumentException when the level is unrecognized
	 */
	public static String normalizeLevel(String raw) {
		if (raw == null) {
			throw new IllegalArgumentException("Risk level must not be null");
		}
		return switch (raw.trim().toLowerCase()) {
			case "error", "critical", "high", "blocker" -> "error";
			case "warning", "medium", "major" -> "warning";
			case "note", "low", "minor", "info" -> "note";
			default -> throw new IllegalArgumentException("Unrecognized risk level: " + raw);
		};
	}
}
