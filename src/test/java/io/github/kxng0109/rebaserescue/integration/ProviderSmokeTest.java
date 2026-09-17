package io.github.kxng0109.rebaserescue.integration;

import io.github.kxng0109.rebaserescue.api.dto.AnalyzeDiffRequest;
import io.github.kxng0109.rebaserescue.api.dto.AnalyzeDiffResponse;
import io.github.kxng0109.rebaserescue.api.dto.RiskItem;
import io.github.kxng0109.rebaserescue.config.PrCopilotAnalysisProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke tests against REAL provider endpoints.
 *
 * <p>Deliberately opt-in: the whole class is disabled unless the
 * environment variable {@code AI_PROVIDER_KEY} is set, so a credential less CI run never touches a network and never
 * leaks a key into a report. Keys arrive via env vars, never committed.
 *
 * <p>Each test makes exactly one real API call and asserts only on
 * response shape (status, non-null body, leveled-risk contract) — never on model content, which is non-deterministic.
 */
@EnabledIfEnvironmentVariable(named = "AI_PROVIDER_KEY", matches = ".+")
@TestPropertySource(properties = {
		"spring.ai.openai.api-key=${OPENAI_API_KEY}",
		"spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}",
		"spring.ai.google.genai.api-key=${GOOGLE_GENAI_API_KEY}",
		"prcopilot.ai.provider=openai",
		"prcopilot.ai.auto-fallback=false",
		"server.port=0"
})
@ExtendWith(MockitoExtension.class)
@WebMvcTest
class ProviderSmokeTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PrCopilotAnalysisProperties analysisProperties;

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private AnalyzeDiffRequest sampleRequest() {
		return AnalyzeDiffRequest.builder()
		                         .requestId("smoke-1")
		                         .diff("diff --git a/Foo.java b/Foo.java\n+++ b/Foo.java\n@@ -1,3 +1,3 @@\n-public void foo() { return null; }\n+public void foo() { return 1; }\n")
		                         .language("java")
		                         .maxSummaryLength(256)
		                         .style("conventional-commit")
		                         .build();
	}

	@Test
	void analyzeDiff_returnsWellShapedResponse() throws Exception {
		when(analysisProperties.getMaxRequestBytes()).thenReturn(1_000_000L);
		String payload = OBJECT_MAPPER.writeValueAsString(sampleRequest());

		MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/analyze-diff")
		                                                         .contentType(MediaType.APPLICATION_JSON)
		                                                         .content(payload))
		                          .andExpect(status().isOk())
		                          .andReturn();

		AnalyzeDiffResponse parsed = OBJECT_MAPPER.readValue(
				result.getResponse().getContentAsByteArray(), AnalyzeDiffResponse.class);

		assertThat(parsed.requestId()).isEqualTo("smoke-1");
		assertThat(parsed.summary()).isNotNull().isNotEmpty();
		for (RiskItem risk : parsed.risks()) {
			assertThat(risk.level()).isNotNull();
		}
	}
}