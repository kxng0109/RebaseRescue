package io.github.kxng0109.rebaserescue.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Builder;

/**
 * Represents a request to analyze a code change diff.
 *
 * @param diff             the diff content to analyze, must not be blank
 * @param language         the language of the diff, may be {@code null} to use a default
 * @param style            the formatting or analysis style, may be {@code null} to use a default
 * @param maxSummaryLength the maximum allowed length for the summary, must be positive
 * @param requestId        a unique identifier for the request, may be {@code null}
 */
@Builder
public record AnalyzeDiffRequest(
		@NotBlank(message = "Diff must not be blank")
		String diff,

		String language,

		String style,

		@Positive(message = "Max summary length must be positive")
		Integer maxSummaryLength,

		@Pattern(regexp = ErrorResponse.REQUEST_ID_PATTERN,
				message = "Request ID must be 1-64 chars of letters, digits, '-' or '_'")
		String requestId
) {
}
