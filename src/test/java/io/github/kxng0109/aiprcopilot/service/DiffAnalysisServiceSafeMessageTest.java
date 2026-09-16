package io.github.kxng0109.aiprcopilot.service;

import io.github.kxng0109.aiprcopilot.error.CustomApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class DiffAnalysisServiceSafeMessageTest {

    @Test
    void streamSafeMessage_shouldPassThrough4xxMessage() {
        assertThat(DiffAnalysisService.streamSafeMessage(
                new CustomApiException("slow down", HttpStatus.TOO_MANY_REQUESTS)))
                .isEqualTo("slow down");
    }

    @Test
    void streamSafeMessage_shouldUseReasonPhraseFor5xx() {
        assertThat(DiffAnalysisService.streamSafeMessage(
                new CustomApiException("Failed to resolve internal-ai-7", HttpStatus.BAD_GATEWAY)))
                .isEqualTo("Bad Gateway");
    }

    @Test
    void streamSafeMessage_shouldUseGenericWhenStatusNull() {
        assertThat(DiffAnalysisService.streamSafeMessage(
                new CustomApiException("mystery", null)))
                .isEqualTo("Streaming failed");
    }

    @Test
    void streamSafeMessage_shouldUseReasonPhraseWhen4xxMessageBlank() {
        assertThat(DiffAnalysisService.streamSafeMessage(
                new CustomApiException("  ", HttpStatus.BAD_REQUEST)))
                .isEqualTo("Bad Request");
    }

    @Test
    void streamSafeMessage_shouldUseGenericForPlainException() {
        assertThat(DiffAnalysisService.streamSafeMessage(new RuntimeException("boom")))
                .isEqualTo("Streaming failed");
    }
}
