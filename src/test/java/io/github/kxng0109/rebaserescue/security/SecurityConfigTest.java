package io.github.kxng0109.rebaserescue.security;

import io.github.kxng0109.rebaserescue.api.dto.AnalyzeDiffRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the deny-by-default security posture with the real filter chain: method restriction, API-key gate, and CORS
 * deny. Request bodies are deliberately invalid (blank diff) so validation rejects them before any AI provider call is
 * attempted — no network leaves the JVM.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private String body(String diff) throws Exception {
		return objectMapper.writeValueAsString(AnalyzeDiffRequest.builder()
		                                                         .diff(diff)
		                                                         .requestId("sec-1")
		                                                         .build());
	}

	@Test
	void getOnApi_shouldBeDenied() throws Exception {
		mockMvc.perform(get("/api/v1/analyze-diff"))
		       .andExpect(status().isForbidden());
	}

	@Test
	void postWithoutApiKey_shouldBeRejected() throws Exception {
		mockMvc.perform(post("/api/v1/analyze-diff")
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(body("x")))
		       .andExpect(status().is4xxClientError());
	}

	@Test
	void postWithWrongApiKey_shouldBeRejected() throws Exception {
		mockMvc.perform(post("/api/v1/analyze-diff")
				                .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-key")
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(body("x")))
		       .andExpect(status().is4xxClientError());
	}

	@Test
	void postWithValidApiKey_shouldPassSecurityToValidation() throws Exception {
		mockMvc.perform(post("/api/v1/analyze-diff")
				                .header(HttpHeaders.AUTHORIZATION, "Bearer test-key")
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(body("  ")))
		       .andExpect(status().isBadRequest());
	}

	@Test
	void preflight_shouldEmitNoAllowOriginHeader() throws Exception {
		mockMvc.perform(options("/api/v1/analyze-diff")
				                .header(HttpHeaders.ORIGIN, "https://evil.example")
				                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
		       .andExpect(status().is4xxClientError())
		       .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}
}
