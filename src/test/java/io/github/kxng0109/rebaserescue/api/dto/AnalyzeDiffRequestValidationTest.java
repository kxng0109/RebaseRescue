package io.github.kxng0109.rebaserescue.api.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyzeDiffRequestValidationTest {

	private static final Validator VALIDATOR =
			Validation.buildDefaultValidatorFactory().getValidator();

	private static AnalyzeDiffRequest request(String diff, Integer maxSummaryLength, String requestId) {
		return AnalyzeDiffRequest.builder()
		                         .diff(diff)
		                         .language("en")
		                         .style("conventional-commits")
		                         .maxSummaryLength(maxSummaryLength)
		                         .requestId(requestId)
		                         .build();
	}

	@Test
	void validRequest_shouldPass() {
		assertThat(VALIDATOR.validate(request("diff", 300, "req-1"))).isEmpty();
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", "  "})
	void diff_shouldRejectNullAndBlank(String diff) {
		assertThat(VALIDATOR.validate(request(diff, 300, "req-1"))).isNotEmpty();
	}

	@ParameterizedTest
	@ValueSource(ints = {0, -5})
	void maxSummaryLength_shouldRejectNonPositive(int value) {
		assertThat(VALIDATOR.validate(request("diff", value, "req-1"))).isNotEmpty();
	}

	@Test
	void maxSummaryLength_shouldAllowNull() {
		assertThat(VALIDATOR.validate(request("diff", null, "req-1"))).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {"a", "req-1", "A-_09"})
	void requestId_shouldAcceptValidShapes(String requestId) {
		assertThat(VALIDATOR.validate(request("diff", 300, requestId))).isEmpty();
	}

	@Test
	void requestId_shouldAccept64Chars() {
		assertThat(VALIDATOR.validate(request("diff", 300, "x".repeat(64)))).isEmpty();
	}

	@Test
	void requestId_shouldReject65Chars() {
		assertThat(VALIDATOR.validate(request("diff", 300, "x".repeat(65)))).isNotEmpty();
	}

	@Test
	void requestId_shouldAllowNull() {
		assertThat(VALIDATOR.validate(request("diff", 300, null))).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {"has space", "has\ttab", "evil\ninjection", "dot.name"})
	void requestId_shouldRejectInvalidShapes(String requestId) {
		assertThat(VALIDATOR.validate(request("diff", 300, requestId))).isNotEmpty();
	}

	@Test
	void languageAndStyle_shouldAllowNull() {
		AnalyzeDiffRequest bare = AnalyzeDiffRequest.builder()
		                                            .diff("diff")
		                                            .requestId("req-1")
		                                            .build();

		assertThat(VALIDATOR.validate(bare)).isEmpty();
	}
}
