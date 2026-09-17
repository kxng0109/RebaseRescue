package io.github.kxng0109.rebaserescue.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorTypesTest {

	@Test
	void blockedDiffException_shouldRoundTripConstructors() {
		assertThat(new BlockedDiffException().getMessage())
				.isEqualTo("Diff blocked by input guardrail");
		assertThat(new BlockedDiffException("custom").getMessage()).isEqualTo("custom");
		RuntimeException cause = new RuntimeException("root");
		BlockedDiffException withCause = new BlockedDiffException("custom", cause);
		assertThat(withCause.getMessage()).isEqualTo("custom");
		assertThat(withCause.getCause()).isSameAs(cause);
	}

	@Test
	void diffTooLargeException_shouldRoundTripConstructors() {
		assertThat(new DiffTooLargeException().getMessage())
				.isEqualTo("Diff exceeded maximum allowed size");
		assertThat(new DiffTooLargeException("custom").getMessage()).isEqualTo("custom");
	}

	@Test
	void modelOutputParseException_shouldRoundTripConstructors() {
		assertThat(new ModelOutputParseException().getMessage())
				.isEqualTo("Error occurred while parsing JSON from the model.");
		assertThat(new ModelOutputParseException("custom").getMessage()).isEqualTo("custom");
	}

	@Test
	void customApiException_shouldExposeStatusAndCause() {
		RuntimeException cause = new RuntimeException("root");

		CustomApiException simple = new CustomApiException("msg", HttpStatus.BAD_REQUEST);
		assertThat(simple.getMessage()).isEqualTo("msg");
		assertThat(simple.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(simple.getCause()).isNull();

		CustomApiException withCause =
				new CustomApiException("msg", HttpStatus.TOO_MANY_REQUESTS, cause);
		assertThat(withCause.getHttpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(withCause.getCause()).isSameAs(cause);
	}
}
