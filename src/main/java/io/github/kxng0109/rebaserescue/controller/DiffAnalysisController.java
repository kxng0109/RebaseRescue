package io.github.kxng0109.rebaserescue.controller;

import io.github.kxng0109.rebaserescue.api.dto.AnalyzeDiffRequest;
import io.github.kxng0109.rebaserescue.api.dto.AnalyzeDiffResponse;
import io.github.kxng0109.rebaserescue.api.dto.ErrorResponse;
import io.github.kxng0109.rebaserescue.error.CustomApiException;
import io.github.kxng0109.rebaserescue.service.DiffAnalysisService;
import io.github.kxng0109.rebaserescue.service.SarifService;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Handles requests to analyze a code change diff.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Diff Analysis", description = "Endpoints for analyzing Git diffs")
public class DiffAnalysisController {

	private final DiffAnalysisService diffAnalysisService;

	private final SarifService sarifService;

	/**
	 * Analyzes a code change diff and returns the results.
	 *
	 * @param request the request containing the diff content, language, style, max summary length, and request ID, must
	 *                not be null
	 * @return the response containing the analysis title, summary, details, risks, suggested tests, touched files,
	 * analysis notes, metadata, request ID, and raw model output, never null
	 * @throws io.github.kxng0109.rebaserescue.error.DiffTooLargeException if the diff content exceeds the maximum
	 *                                                                    allowed size
	 */

	@Operation(
			summary = "Analyze a Git diff using AI code review",
			description = "Accepts a unified Git diff and returns an AI-generated structured analysis, risk list, suggested tests, and more."
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Successfully analyzed"),
			@ApiResponse(responseCode = "400", description = "Validation error (e.g., blank diff)"),
			@ApiResponse(responseCode = "413", description = "Diff too large"),
			@ApiResponse(responseCode = "422", description = "AI model returned invalid output"),
			@ApiResponse(responseCode = "429", description = "Rate limit or provider concurrency exceeded"),
			@ApiResponse(responseCode = "500", description = "Internal server error")
	})
	@PostMapping(value = "/analyze-diff", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	@RateLimiter(name = "api", fallbackMethod = "analyzeDiffRateLimited")
	public ResponseEntity<AnalyzeDiffResponse> analyzeDiff(@Valid @RequestBody AnalyzeDiffRequest request) {
		AnalyzeDiffResponse response = diffAnalysisService.analyzeDiff(request);
		return ResponseEntity.ok(response);
	}

	@SuppressWarnings("unused")
	ResponseEntity<ErrorResponse> analyzeDiffRateLimited(
			@RequestBody AnalyzeDiffRequest request, RequestNotPermitted ex) {
		HttpStatus status = HttpStatus.TOO_MANY_REQUESTS;
		return ResponseEntity.status(status).body(ErrorResponse.builder()
		                                                       .timestamp(OffsetDateTime.now())
		                                                       .statusCode(status.value())
		                                                       .error(status.getReasonPhrase())
		                                                       .message("Rate limit exceeded, please retry shortly")
		                                                       .path("/api/v1/analyze-diff")
		                                                       .requestId(request != null ? request.requestId() : null)
		                                                       .build());
	}

	@Operation(
			summary = "Analyze a Git diff and return SARIF 2.1.0",
			description = "Same analysis as /analyze-diff, rendered as SARIF for GitHub Code Scanning or SonarQube import. Findings below the configured minimum level are filtered at emit time."
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "SARIF document"),
			@ApiResponse(responseCode = "400", description = "Validation error (e.g., blank diff)"),
			@ApiResponse(responseCode = "413", description = "Diff too large"),
			@ApiResponse(responseCode = "422", description = "AI model returned invalid output"),
			@ApiResponse(responseCode = "429", description = "Rate limit or provider concurrency exceeded"),
			@ApiResponse(responseCode = "500", description = "Internal server error")
	})
	@PostMapping(value = "/analyze-diff/sarif", consumes = MediaType.APPLICATION_JSON_VALUE, produces = "application/sarif+json")
	@RateLimiter(name = "api", fallbackMethod = "analyzeSarifRateLimited")
	public ResponseEntity<Map<String, Object>> analyzeDiffSarif(
			@Valid @RequestBody AnalyzeDiffRequest request) {
		AnalyzeDiffResponse response = diffAnalysisService.analyzeDiff(request);
		return ResponseEntity.ok(sarifService.toSarif(response));
	}

	@SuppressWarnings("unused")
	ResponseEntity<ErrorResponse> analyzeSarifRateLimited(
			@RequestBody AnalyzeDiffRequest request, RequestNotPermitted ex) {
		HttpStatus status = HttpStatus.TOO_MANY_REQUESTS;
		return ResponseEntity.status(status).body(ErrorResponse.builder()
		                                                       .timestamp(OffsetDateTime.now())
		                                                       .statusCode(status.value())
		                                                       .error(status.getReasonPhrase())
		                                                       .message("Rate limit exceeded, please retry shortly")
		                                                       .path("/api/v1/analyze-diff/sarif")
		                                                       .requestId(request != null ? request.requestId() : null)
		                                                       .build());
	}

	@Operation(
			summary = "Stream a Git diff analysis over SSE",
			description = "Emits started, per-chunk token, result and done events. Cache hits emit the stored result immediately."
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Event stream"),
			@ApiResponse(responseCode = "400", description = "Validation error (e.g., blank diff)"),
			@ApiResponse(responseCode = "413", description = "Diff too large"),
			@ApiResponse(responseCode = "429", description = "Rate limit or provider concurrency exceeded")
	})
	@PostMapping(value = "/analyze-diff/stream", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	@RateLimiter(name = "api", fallbackMethod = "analyzeStreamRateLimited")
	public SseEmitter analyzeDiffStream(
			@Valid @RequestBody AnalyzeDiffRequest request) {
		return diffAnalysisService.streamAnalyze(request);
	}

	@SuppressWarnings("unused")
	SseEmitter analyzeStreamRateLimited(
			@RequestBody AnalyzeDiffRequest request, RequestNotPermitted ex) {
		SseEmitter emitter = new SseEmitter();
		emitter.completeWithError(new CustomApiException(
				"Rate limit exceeded, please retry shortly", HttpStatus.TOO_MANY_REQUESTS, ex));
		return emitter;
	}
}
