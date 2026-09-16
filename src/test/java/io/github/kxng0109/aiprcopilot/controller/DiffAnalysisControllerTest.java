package io.github.kxng0109.aiprcopilot.controller;

import io.github.kxng0109.aiprcopilot.api.dto.AiCallMetadata;
import io.github.kxng0109.aiprcopilot.api.dto.AnalyzeDiffRequest;
import io.github.kxng0109.aiprcopilot.api.dto.AnalyzeDiffResponse;
import io.github.kxng0109.aiprcopilot.api.dto.ErrorResponse;
import io.github.kxng0109.aiprcopilot.error.DiffTooLargeException;
import io.github.kxng0109.aiprcopilot.service.DiffAnalysisService;
import io.github.kxng0109.aiprcopilot.service.SarifService;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DiffAnalysisController.class)
@AutoConfigureMockMvc(addFilters = false)
public class DiffAnalysisControllerTest {
	@MockitoBean
	private DiffAnalysisService diffAnalysisService;

	@MockitoBean
	private SarifService sarifService;

	@MockitoBean
	private io.github.kxng0109.aiprcopilot.config.PrCopilotAnalysisProperties analysisProperties;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	public void analyzeDiff_shouldReturn200Ok_whenRequestIsValid() throws Exception {
		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .requestId("req-1")
		                                               .diff("diff-1")
		                                               .language("en")
		                                               .maxSummaryLength(1024)
		                                               .style("conventional-commit")
		                                               .build();

		AiCallMetadata metadata = AiCallMetadata.builder()
		                                        .modelLatencyMs(100)
		                                        .modelName("gpt-4o")
		                                        .tokensUsed(100)
		                                        .build();

		AnalyzeDiffResponse response = AnalyzeDiffResponse.builder()
		                                                  .requestId("req-1")
		                                                  .metadata(metadata)
		                                                  .analysisNotes(null)
		                                                  .details("Some details")
		                                                  .rawModelOutput(null)
		                                                  .risks(List.of())
		                                                  .suggestedTests(List.of())
		                                                  .touchedFiles(List.of("afile.txt"))
		                                                  .summary("Some summary")
		                                                  .title("Some title")
		                                                  .build();

		Mockito.when(diffAnalysisService.analyzeDiff(request))
		       .thenReturn(response);

		mockMvc.perform(post("/api/v1/analyze-diff")
				                .content(objectMapper.writeValueAsString(request))
				                .contentType(MediaType.APPLICATION_JSON))
		       .andExpect(status().isOk())
		       .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
		       .andExpect(jsonPath("$.title").value(response.title()));

		verify(diffAnalysisService).analyzeDiff(any(AnalyzeDiffRequest.class));
	}

	@Test
	public void analyzeDiff_shouldThrow400BadRequest_whenDIffIsBlank() throws Exception {
		AnalyzeDiffRequest invalidRequest = AnalyzeDiffRequest.builder()
		                                                      .diff("  ")
		                                                      .language("en")
		                                                      .style("conventional-commits")
		                                                      .maxSummaryLength(300)
		                                                      .requestId("req-2")
		                                                      .build();

		mockMvc.perform(post("/api/v1/analyze-diff")
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(objectMapper.writeValueAsString(invalidRequest)))
		       .andExpect(status().isBadRequest())
		       .andExpect(jsonPath("$.statusCode").value(400))
		       .andExpect(jsonPath("$.message").value("Validation failed"))
		       .andExpect(jsonPath("$.validationErrors.diff").value("Diff must not be blank"))
		       .andExpect(jsonPath("$.path").value("/api/v1/analyze-diff"));

		verify(diffAnalysisService, never()).analyzeDiff(any(AnalyzeDiffRequest.class));
	}

	@Test
	public void analyzeDiff_shouldEchoRequestIdHeader_whenPresent() throws Exception {
		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("  ")
		                                               .language("en")
		                                               .style("conventional-commits")
		                                               .maxSummaryLength(300)
		                                               .requestId("req-2")
		                                               .build();

		mockMvc.perform(post("/api/v1/analyze-diff")
				                .header("X-Request-ID", "corr-123")
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(objectMapper.writeValueAsString(request)))
		       .andExpect(status().isBadRequest())
		       .andExpect(jsonPath("$.requestId").value("corr-123"));

		verify(diffAnalysisService, never()).analyzeDiff(any(AnalyzeDiffRequest.class));
	}

	@Test
	public void analyzeDiff_shouldRejectMaliciousRequestId() throws Exception {
		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("some diff")
		                                               .language("en")
		                                               .style("conventional-commits")
		                                               .maxSummaryLength(300)
		                                               .requestId("evil\ninjection")
		                                               .build();

		mockMvc.perform(post("/api/v1/analyze-diff")
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(objectMapper.writeValueAsString(request)))
		       .andExpect(status().isBadRequest())
		       .andExpect(jsonPath("$.validationErrors.requestId").exists());

		verify(diffAnalysisService, never()).analyzeDiff(any(AnalyzeDiffRequest.class));
	}

	@Test
	void analyzeDiffRateLimited_shouldHandleNullRequest() {
		DiffAnalysisController controller =
				new DiffAnalysisController(diffAnalysisService, sarifService);

		ResponseEntity<ErrorResponse> response = controller.analyzeDiffRateLimited(
				null, RequestNotPermitted.createRequestNotPermitted(RateLimiter.ofDefaults("test")));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(response.getBody().requestId()).isNull();
		assertThat(response.getBody().path()).isEqualTo("/api/v1/analyze-diff");
	}

	@Test
	void analyzeDiffRateLimited_shouldEchoRequestId_whenPresent() {
		DiffAnalysisController controller =
				new DiffAnalysisController(diffAnalysisService, sarifService);
		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("d")
		                                               .requestId("req-9")
		                                               .build();

		ResponseEntity<ErrorResponse> response = controller.analyzeDiffRateLimited(
				request, RequestNotPermitted.createRequestNotPermitted(RateLimiter.ofDefaults("test")));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(response.getBody().requestId()).isEqualTo("req-9");
	}

	@Test
	void analyzeSarifRateLimited_shouldShape429() {
		DiffAnalysisController controller =
				new DiffAnalysisController(diffAnalysisService, sarifService);
		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("d")
		                                               .requestId("req-7")
		                                               .build();

		ResponseEntity<ErrorResponse> response = controller.analyzeSarifRateLimited(
				request, RequestNotPermitted.createRequestNotPermitted(RateLimiter.ofDefaults("test")));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(response.getBody().requestId()).isEqualTo("req-7");
		assertThat(response.getBody().path()).isEqualTo("/api/v1/analyze-diff/sarif");
	}

	@Test
	void analyzeSarifRateLimited_shouldHandleNullRequest() {
		DiffAnalysisController controller =
				new DiffAnalysisController(diffAnalysisService, sarifService);

		ResponseEntity<ErrorResponse> response = controller.analyzeSarifRateLimited(
				null, RequestNotPermitted.createRequestNotPermitted(RateLimiter.ofDefaults("test")));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(response.getBody().requestId()).isNull();
	}

	@Test
	void analyzeStreamRateLimited_shouldReturnFailingEmitter() {
		DiffAnalysisController controller =
				new DiffAnalysisController(diffAnalysisService, sarifService);
		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("d")
		                                               .requestId("req-3")
		                                               .build();

		SseEmitter emitter = controller.analyzeStreamRateLimited(
				request, RequestNotPermitted.createRequestNotPermitted(RateLimiter.ofDefaults("test")));

		assertThat(emitter).isNotNull();
	}

	@Test
	void analyzeDiffStream_shouldReturn400_whenDiffIsBlank() throws Exception {
		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("  ")
		                                               .language("en")
		                                               .style("conventional-commits")
		                                               .maxSummaryLength(300)
		                                               .requestId("req-1")
		                                               .build();

		mockMvc.perform(post("/api/v1/analyze-diff/stream")
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(objectMapper.writeValueAsString(request)))
		       .andExpect(status().isBadRequest());

		verify(diffAnalysisService, never()).streamAnalyze(any(AnalyzeDiffRequest.class));
	}

	@Test
	void analyzeDiff_shouldReturn400BadRequest_whenDiffIsMissing() throws Exception {
		String invalidJson = """
				{
				  "language": "en",
				  "style": "conventional-commits"
				}
				""";

		mockMvc.perform(post("/api/v1/analyze-diff")
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(invalidJson))
		       .andExpect(status().isBadRequest());

		verify(diffAnalysisService, never()).analyzeDiff(any(AnalyzeDiffRequest.class));
	}

	@Test
	void analyzeDiff_shouldReturn400MethodArgumentNotValidException_whenRequestBodyIsMissing() throws Exception {
		mockMvc.perform(post("/api/v1/analyze-diff")
				                .contentType(MediaType.APPLICATION_JSON))
		       .andExpect(status().isBadRequest())
		       .andExpect(jsonPath("$.statusCode").value(400))
		       .andExpect(jsonPath("$.message").value("Request body is missing. JSON object required."));

		verify(diffAnalysisService, never()).analyzeDiff(any(AnalyzeDiffRequest.class));
	}

	@Test
	void analyzeDiff_shouldReturn405MethodNotAllowed_whenUsingWrongHttpMethod() throws Exception {
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				                .get("/api/v1/analyze-diff"))
		       .andExpect(status().isMethodNotAllowed())
		       .andExpect(jsonPath("$.statusCode").value(405));

		verify(diffAnalysisService, never()).analyzeDiff(any(AnalyzeDiffRequest.class));
	}

	@Test
	public void analyzeDiff_shouldThrow413DiffTooLargeException_whenDiffIsTooLarge() throws Exception {
		int maxDiffChars = 1024;
		String diff = "x".repeat(maxDiffChars + 1);

		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff(diff)
		                                               .maxSummaryLength(1024)
		                                               .requestId("req-1")
		                                               .build();

		when(diffAnalysisService.analyzeDiff(any(AnalyzeDiffRequest.class)))
				.thenThrow(new DiffTooLargeException());

		mockMvc.perform(post("/api/v1/analyze-diff")
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(objectMapper.writeValueAsString(request)))
		       .andExpect(status().isContentTooLarge())
		       .andExpect(jsonPath("$.statusCode").value(413))
		       .andExpect(jsonPath("$.message").value("Diff exceeded maximum allowed size"))
		       .andExpect(jsonPath("$.path").value("/api/v1/analyze-diff"));

		verify(diffAnalysisService).analyzeDiff(any(AnalyzeDiffRequest.class));
	}

	@Test
	public void shouldThrow404NoResourceFoundException_whenEndpointDoesNotExist() throws Exception {
		mockMvc.perform(post("/api/v1/does-not-exist")
				                .contentType(MediaType.APPLICATION_JSON))
		       .andExpect(status().isNotFound())
		       .andExpect(jsonPath("$.statusCode").value(404))
		       .andExpect(jsonPath("$.path").value("/api/v1/does-not-exist"));

		verify(diffAnalysisService, never()).analyzeDiff(any(AnalyzeDiffRequest.class));
	}

	@Test
	public void analyzeDiff_shouldReturnGeneric500_whenErrorIsGeneric() throws Exception {
		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("x")
		                                               .maxSummaryLength(1024)
		                                               .requestId("req-1")
		                                               .build();

		when(diffAnalysisService.analyzeDiff(any(AnalyzeDiffRequest.class)))
				.thenThrow(new RuntimeException("boommmmmm!!!"));

		mockMvc.perform(post("/api/v1/analyze-diff")
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(objectMapper.writeValueAsString(request)))
		       .andExpect(status().isInternalServerError())
		       .andExpect(jsonPath("$.statusCode").value(500))
		       .andExpect(jsonPath("$.message").value("Internal Server Error"))
		       .andExpect(jsonPath("$.path").value("/api/v1/analyze-diff"));

		verify(diffAnalysisService).analyzeDiff(any(AnalyzeDiffRequest.class));
	}

	@Test
	public void analyzeDiffSarif_shouldReturnSarifDocument() throws Exception {
		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("x")
		                                               .maxSummaryLength(1024)
		                                               .requestId("req-1")
		                                               .build();

		AnalyzeDiffResponse analysis = AnalyzeDiffResponse.builder()
		                                                  .title("t")
		                                                  .summary("s")
		                                                  .risks(List.of())
		                                                  .riskScore(0)
		                                                  .requestId("req-1")
		                                                  .build();

		when(diffAnalysisService.analyzeDiff(any(AnalyzeDiffRequest.class))).thenReturn(analysis);
		when(sarifService.toSarif(analysis)).thenReturn(java.util.Map.of("version", "2.1.0"));

		mockMvc.perform(post("/api/v1/analyze-diff/sarif")
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(objectMapper.writeValueAsString(request)))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.version").value("2.1.0"));

		verify(diffAnalysisService).analyzeDiff(any(AnalyzeDiffRequest.class));
		verify(sarifService).toSarif(analysis);
	}

	@Test
	public void analyzeDiffStream_shouldReturnEventStream() throws Exception {
		AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
		                                               .diff("x")
		                                               .maxSummaryLength(1024)
		                                               .requestId("req-1")
		                                               .build();

		org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
				new org.springframework.web.servlet.mvc.method.annotation.SseEmitter();
		emitter.complete();

		when(diffAnalysisService.streamAnalyze(any(AnalyzeDiffRequest.class))).thenReturn(emitter);

		mockMvc.perform(post("/api/v1/analyze-diff/stream")
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(objectMapper.writeValueAsString(request)))
		       .andExpect(status().isOk());

		verify(diffAnalysisService).streamAnalyze(any(AnalyzeDiffRequest.class));
	}
}
