package io.github.kxng0109.rebaserescue.service;

import io.github.kxng0109.rebaserescue.api.dto.AnalyzeDiffResponse;
import io.github.kxng0109.rebaserescue.api.dto.RiskItem;
import io.github.kxng0109.rebaserescue.config.PrCopilotAnalysisProperties;
import io.github.kxng0109.rebaserescue.config.PrCopilotLoggingProperties;
import io.github.kxng0109.rebaserescue.error.ModelOutputParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DiffResponseMapperServiceTest {

	@Mock
	private PrCopilotLoggingProperties loggingProperties;

	@Mock
	private PrCopilotAnalysisProperties analysisProperties;

	private ObjectMapper objectMapper;
	private DiffResponseMapperService mapperService;

	@BeforeEach
	void setup() {
		objectMapper = JsonMapper.builder().build();

		lenient().when(analysisProperties.isIncludeRawModelOutput()).thenReturn(false);
		lenient().when(analysisProperties.getMaxModelOutputChars()).thenReturn(1000000);
		lenient().when(analysisProperties.getMaxRisks()).thenReturn(200);
		lenient().when(analysisProperties.getMaxSuggestedTests()).thenReturn(100);
		lenient().when(analysisProperties.getMaxTouchedFiles()).thenReturn(500);
		lenient().when(loggingProperties.isLogResponses()).thenReturn(false);

		mapperService = new DiffResponseMapperService(
				objectMapper,
				loggingProperties,
				analysisProperties
		);
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldParseValidJsonResponse() {
		String validJson = """
				{
				  "title": "feat: add new feature",
				  "summary": "Added a new feature to the system",
				  "details": "Detailed implementation of the feature",
				  "risks": [{"level": "warning", "message": "Risk 1"}, {"level": "error", "message": "Risk 2"}],
				  "suggestedTests": ["Test 1", "Test 2"],
				  "touchedFiles": ["file1.java", "file2.java"],
				  "analysisNotes": "Some notes"
				}
				""";

		ChatResponse response = createChatResponse(validJson);
		String diff = "diff --git a/file1.java b/file1.java";

		AnalyzeDiffResponse result = mapperService.mapToAnalyzeDiffResponse(
				response, 1000L, diff, "req-1", "openai"
		);

		assertNotNull(result);
		assertEquals("feat: add new feature", result.title());
		assertEquals("Added a new feature to the system", result.summary());
		assertEquals("Detailed implementation of the feature", result.details());
		assertThat(result.risks()).containsExactly(
				new io.github.kxng0109.rebaserescue.api.dto.RiskItem("warning", "Risk 1"),
				new io.github.kxng0109.rebaserescue.api.dto.RiskItem("error", "Risk 2")
		);
		assertThat(result.riskScore()).isEqualTo(35);
		assertThat(result.suggestedTests()).containsExactly("Test 1", "Test 2");
		assertThat(result.touchedFiles()).containsExactly("file1.java", "file2.java");
		assertEquals("Some notes", result.analysisNotes());
		assertEquals("req-1", result.requestId());
		assertEquals("openai", result.metadata().provider());
		assertEquals(1000L, result.metadata().modelLatencyMs());
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldExtractTouchedFiles_whenNotProvidedByModel() {
		String jsonWithoutFiles = """
				{
				  "title": "test",
				  "summary": "summary",
				  "details": "details",
				  "risks": [],
				  "suggestedTests": [],
				  "touchedFiles": [],
				  "analysisNotes": null
				}
				""";

		String diff = """
				diff --git a/src/main/File1.java b/src/main/File1.java
				index abc..def
				--- a/src/main/File1.java
				+++ b/src/main/File1.java
				diff --git a/src/test/File2.java b/src/test/File2.java
				""";

		ChatResponse response = createChatResponse(jsonWithoutFiles);

		AnalyzeDiffResponse result = mapperService.mapToAnalyzeDiffResponse(
				response, 500L, diff, "req-2", "anthropic"
		);

		assertThat(result.touchedFiles()).containsExactly(
				"src/main/File1.java",
				"src/test/File2.java"
		);
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldSanitizeJsonWithMarkdownFences() {
		String jsonWithFences = """
				```json
				{
				  "title": "test",
				  "summary": "summary",
				  "details": "details",
				  "risks": [],
				  "suggestedTests": [],
				  "touchedFiles": [],
				  "analysisNotes": null
				}
				```
				""";

		ChatResponse response = createChatResponse(jsonWithFences);

		AnalyzeDiffResponse result = mapperService.mapToAnalyzeDiffResponse(
				response, 100L, "diff", "req-3", "gemini"
		);

		assertNotNull(result);
		assertEquals("test", result.title());
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldExtractJsonFromSurroundingText() {
		String responseWithExtra = """
				Sure, here's the analysis:
				
				{
				  "title": "test",
				  "summary": "summary",
				  "details": "details",
				  "risks": [],
				  "suggestedTests": [],
				  "touchedFiles": [],
				  "analysisNotes": null
				}
				
				Hope this helps!
				""";

		ChatResponse response = createChatResponse(responseWithExtra);

		AnalyzeDiffResponse result = mapperService.mapToAnalyzeDiffResponse(
				response, 100L, "diff", "req-4", "openai"
		);

		assertNotNull(result);
		assertEquals("test", result.title());
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldIncludeRawOutput_whenConfigured() {
		when(analysisProperties.isIncludeRawModelOutput()).thenReturn(true);

		String json = """
				{"title":"test","summary":"s","details":"d","risks":[],"suggestedTests":[],"touchedFiles":[],"analysisNotes":null}
				""";

		ChatResponse response = createChatResponse(json);

		AnalyzeDiffResponse result = mapperService.mapToAnalyzeDiffResponse(
				response, 100L, "diff", "req-5", "openai"
		);

		assertNotNull(result.rawModelOutput());
		assertThat(result.rawModelOutput()).contains("test");
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldThrowException_whenJsonIsInvalid() {
		String invalidJson = "not valid json at all";
		ChatResponse response = createChatResponse(invalidJson);

		assertThrows(
				ModelOutputParseException.class, () ->
						mapperService.mapToAnalyzeDiffResponse(
								response, 100L, "diff", "req-6", "openai"
						)
		);
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldThrowException_whenRequiredFieldsMissing() {
		String incompleteJson = """
				{
				  "title": "test"
				}
				""";

		ChatResponse response = createChatResponse(incompleteJson);

		ModelOutputParseException exception = assertThrows(
				ModelOutputParseException.class,
				() -> mapperService.mapToAnalyzeDiffResponse(
						response, 100L, "diff", "req-7", "openai"
				)
		);

		assertThat(exception.getMessage()).contains("missing required fields");
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldHandleNullTokenUsage() {
		String json = """
				{"title":"test","summary":"s","details":"d","risks":[],"suggestedTests":[],"touchedFiles":[],"analysisNotes":null}
				""";

		ChatResponse response = createChatResponseWithNullUsage(json);

		AnalyzeDiffResponse result = mapperService.mapToAnalyzeDiffResponse(
				response, 100L, "diff", "req-8", "ollama"
		);

		assertNotNull(result);
		assertNull(result.metadata().tokensUsed());
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldNormalizeRiskLevels() {
		String json = """
				{"title":"test","summary":"s","details":"d",
				 "risks":[{"level":"CRITICAL","message":" SQL injection "},{"level":"Medium","message":"slow query"}],
				 "suggestedTests":[],"touchedFiles":[],"analysisNotes":null}
				""";

		ChatResponse response = createChatResponse(json);

		AnalyzeDiffResponse result = mapperService.mapToAnalyzeDiffResponse(
				response, 100L, "diff", "req-9", "openai"
		);

		assertThat(result.risks()).containsExactly(
				new io.github.kxng0109.rebaserescue.api.dto.RiskItem("error", "SQL injection"),
				new io.github.kxng0109.rebaserescue.api.dto.RiskItem("warning", "slow query")
		);
		assertThat(result.riskScore()).isEqualTo(35);
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldTruncateOversizedLists() {
		StringBuilder risks = new StringBuilder();
		for (int i = 0; i < 250; i++) {
			if (i > 0) {
				risks.append(',');
			}
			risks.append("{\"level\":\"note\",\"message\":\"risk ").append(i).append("\"}");
		}
		StringBuilder tests = new StringBuilder();
		for (int i = 0; i < 120; i++) {
			if (i > 0) {
				tests.append(',');
			}
			tests.append("\"test ").append(i).append("\"");
		}
		StringBuilder files = new StringBuilder();
		for (int i = 0; i < 600; i++) {
			if (i > 0) {
				files.append(',');
			}
			files.append("\"file").append(i).append(".java\"");
		}
		String json = "{\"title\":\"test\",\"summary\":\"s\",\"details\":\"d\","
				+ "\"risks\":[" + risks + "],"
				+ "\"suggestedTests\":[" + tests + "],"
				+ "\"touchedFiles\":[" + files + "],\"analysisNotes\":null}";

		AnalyzeDiffResponse result = mapperService.mapToAnalyzeDiffResponse(
				createChatResponse(json), 100L, "diff", "req-cap", "openai");

		assertThat(result.risks()).hasSize(200);
		assertThat(result.suggestedTests()).hasSize(100);
		assertThat(result.touchedFiles()).hasSize(500);
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldThrow_whenOutputTooLarge() {
		when(analysisProperties.getMaxModelOutputChars()).thenReturn(10);
		String json = "{\"title\":\"test\",\"summary\":\"s\",\"details\":\"d\","
				+ "\"risks\":[],\"suggestedTests\":[],\"touchedFiles\":[],\"analysisNotes\":null}";

		ModelOutputParseException exception = assertThrows(
				ModelOutputParseException.class,
				() -> mapperService.mapToAnalyzeDiffResponse(
						createChatResponse(json), 100L, "diff", "req-1", "openai")
		);
		assertThat(exception.getMessage()).contains("exceeded maximum allowed size");
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldKeepExactBoundaryLists() {
		StringBuilder risks = new StringBuilder();
		for (int i = 0; i < 200; i++) {
			if (i > 0) {
				risks.append(',');
			}
			risks.append("{\"level\":\"note\",\"message\":\"risk ").append(i).append("\"}");
		}
		String json = "{\"title\":\"test\",\"summary\":\"s\",\"details\":\"d\","
				+ "\"risks\":[" + risks + "],"
				+ "\"suggestedTests\":[],\"touchedFiles\":[],\"analysisNotes\":null}";

		AnalyzeDiffResponse result = mapperService.mapToAnalyzeDiffResponse(
				createChatResponse(json), 100L, "diff", "req-1", "openai");

		assertThat(result.risks()).hasSize(200);
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldParseFencedAndPrefixedJson() {
		String json = "Here is the analysis:\n```json\n"
				+ "{\"title\":\"test\",\"summary\":\"s\",\"details\":\"d\","
				+ "\"risks\":[],\"suggestedTests\":[],\"touchedFiles\":[],\"analysisNotes\":null}\n```";

		AnalyzeDiffResponse result = mapperService.mapToAnalyzeDiffResponse(
				createChatResponse(json), 100L, "diff", "req-1", "openai");

		assertThat(result.title()).isEqualTo("test");
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldReturnEmptyTouchedFiles_whenDiffHasNoGitHeaders() {
		String json = "{\"title\":\"test\",\"summary\":\"s\",\"details\":\"d\","
				+ "\"risks\":[],\"suggestedTests\":[],\"touchedFiles\":null,\"analysisNotes\":null}";

		AnalyzeDiffResponse result = mapperService.mapToAnalyzeDiffResponse(
				createChatResponse(json), 100L, "plain text without git headers", "req-1", "openai");

		assertThat(result.touchedFiles()).isEmpty();
	}

	@Test
	void riskScore_shouldCapAt100AndWeightNotes() {
		List<RiskItem> errors = List.of(
				new RiskItem("error", "a"),
				new RiskItem("error", "b"),
				new RiskItem("error", "c"),
				new RiskItem("error", "d"),
				new RiskItem("error", "e")
		);
		assertThat(DiffResponseMapperService.riskScore(errors)).isEqualTo(100);

		List<RiskItem> notes = List.of(new RiskItem("note", "a"), new RiskItem("note", "b"));
		assertThat(DiffResponseMapperService.riskScore(notes)).isEqualTo(4);

		assertThat(DiffResponseMapperService.riskScore(List.of())).isZero();
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldLogRawOutput_whenDebugEnabled() {
		ch.qos.logback.classic.Logger logger =
				(ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
						DiffResponseMapperService.class);
		ch.qos.logback.classic.Level previous = logger.getLevel();
		logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
		try {
			String json = "{\"title\":\"test\",\"summary\":\"s\",\"details\":\"d\","
					+ "\"risks\":[],\"suggestedTests\":[],\"touchedFiles\":[],\"analysisNotes\":null}";

			AnalyzeDiffResponse result = mapperService.mapToAnalyzeDiffResponse(
					createChatResponse(json), 100L, "diff", "req-1", "openai");

			assertThat(result.title()).isEqualTo("test");
		} finally {
			logger.setLevel(previous);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"title", "summary", "details", "risks", "suggestedTests"})
	void mapToAnalyzeDiffResponse_shouldThrow_whenRequiredFieldMissing(String field) {
		Map<String, Object> doc = new LinkedHashMap<>();
		doc.put("title", "test");
		doc.put("summary", "s");
		doc.put("details", "d");
		doc.put("risks", List.of());
		doc.put("suggestedTests", List.of());
		doc.put("touchedFiles", List.of());
		doc.put("analysisNotes", null);
		doc.put(field, null);
		String json;
		try {
			json = new JsonMapper().writeValueAsString(doc);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		assertThrows(
				ModelOutputParseException.class, () -> mapperService.mapToAnalyzeDiffResponse(
						createChatResponse(json), 100L, "diff", "req-1", "openai")
		);
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldLogResponses_whenEnabled() {
		when(loggingProperties.isLogResponses()).thenReturn(true);
		String json = "{\"title\":\"test\",\"summary\":\"s\",\"details\":\"d\","
				+ "\"risks\":[],\"suggestedTests\":[],\"touchedFiles\":[],\"analysisNotes\":null}";

		AnalyzeDiffResponse result = mapperService.mapToAnalyzeDiffResponse(
				createChatResponse(json), 100L, "diff", "req-1", "openai");

		assertThat(result.title()).isEqualTo("test");
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldNullTokens_whenUsageHasNoTotal() {
		Usage usage = mock(Usage.class);
		when(usage.getTotalTokens()).thenReturn(null);
		ChatResponseMetadata metadata = ChatResponseMetadata.builder()
		                                                    .model("gpt-4o")
		                                                    .usage(usage)
		                                                    .build();
		String json = "{\"title\":\"test\",\"summary\":\"s\",\"details\":\"d\","
				+ "\"risks\":[],\"suggestedTests\":[],\"touchedFiles\":[],\"analysisNotes\":null}";
		ChatResponse response = ChatResponse.builder()
		                                    .generations(List.of(new Generation(new AssistantMessage(json))))
		                                    .metadata(metadata)
		                                    .build();

		AnalyzeDiffResponse result = mapperService.mapToAnalyzeDiffResponse(
				response, 100L, "diff", "req-1", "openai");

		assertThat(result.metadata().tokensUsed()).isNull();
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldWrapUnexpectedErrors() {
		ChatResponse response = mock(ChatResponse.class);
		Generation generation = new Generation(new AssistantMessage(
				"{\"title\":\"t\",\"summary\":\"s\",\"details\":\"d\","
						+ "\"risks\":[],\"suggestedTests\":[],\"touchedFiles\":[],\"analysisNotes\":null}"));
		when(response.getResult()).thenReturn(generation);
		when(response.getMetadata()).thenReturn(null);

		RuntimeException exception = assertThrows(
				RuntimeException.class,
				() -> mapperService.mapToAnalyzeDiffResponse(
						response, 100L, "diff", "req-1", "openai")
		);
		assertThat(exception.getMessage()).contains("Unexpected error mapping AI output");
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldThrow_whenRiskItemNull() {
		String json = "{\"title\":\"test\",\"summary\":\"s\",\"details\":\"d\","
				+ "\"risks\":[null],\"suggestedTests\":[],\"touchedFiles\":[],\"analysisNotes\":null}";

		assertThrows(
				ModelOutputParseException.class, () -> mapperService.mapToAnalyzeDiffResponse(
						createChatResponse(json), 100L, "diff", "req-1", "openai")
		);
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldThrow_whenRiskMessageNull() {
		String json = "{\"title\":\"test\",\"summary\":\"s\",\"details\":\"d\","
				+ "\"risks\":[{\"level\":\"note\",\"message\":null}],"
				+ "\"suggestedTests\":[],\"touchedFiles\":[],\"analysisNotes\":null}";

		assertThrows(
				ModelOutputParseException.class, () -> mapperService.mapToAnalyzeDiffResponse(
						createChatResponse(json), 100L, "diff", "req-1", "openai")
		);
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldThrow_whenRiskMessageBlank() {
		String json = "{\"title\":\"test\",\"summary\":\"s\",\"details\":\"d\","
				+ "\"risks\":[{\"level\":\"note\",\"message\":\"  \"}],"
				+ "\"suggestedTests\":[],\"touchedFiles\":[],\"analysisNotes\":null}";

		assertThrows(
				ModelOutputParseException.class, () -> mapperService.mapToAnalyzeDiffResponse(
						createChatResponse(json), 100L, "diff", "req-1", "openai")
		);
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldThrow_whenResultMissing() {
		ChatResponse response = mock(ChatResponse.class);
		when(response.getResult()).thenThrow(new RuntimeException("no result"));

		assertThrows(
				ModelOutputParseException.class, () -> mapperService.mapToAnalyzeDiffResponse(
						response, 100L, "diff", "req-1", "openai")
		);
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldThrow_whenTextNull() {
		AssistantMessage message = mock(AssistantMessage.class);
		Generation generation = mock(Generation.class);
		when(generation.getOutput()).thenReturn(message);
		ChatResponse response = mock(ChatResponse.class);
		when(response.getResult()).thenReturn(generation);

		assertThrows(
				ModelOutputParseException.class, () -> mapperService.mapToAnalyzeDiffResponse(
						response, 100L, "diff", "req-1", "openai")
		);
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldThrow_whenTextBlank() {
		ChatResponse response = mock(ChatResponse.class);
		when(response.getResult()).thenReturn(new Generation(new AssistantMessage("   ")));

		assertThrows(
				ModelOutputParseException.class, () -> mapperService.mapToAnalyzeDiffResponse(
						response, 100L, "diff", "req-1", "openai")
		);
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldThrow_whenNoBraces() {
		ChatResponse response = createChatResponse("just some prose without braces");

		assertThrows(
				ModelOutputParseException.class, () -> mapperService.mapToAnalyzeDiffResponse(
						response, 100L, "diff", "req-1", "openai")
		);
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldThrow_whenBracesReversed() {
		ChatResponse response = createChatResponse("} reversed { braces");

		assertThrows(
				ModelOutputParseException.class, () -> mapperService.mapToAnalyzeDiffResponse(
						response, 100L, "diff", "req-1", "openai")
		);
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldThrow_whenCloseBraceMissing() {
		ChatResponse response = createChatResponse("prefix {\"title\":\"t\"");

		assertThrows(
				ModelOutputParseException.class, () -> mapperService.mapToAnalyzeDiffResponse(
						response, 100L, "diff", "req-1", "openai")
		);
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldReturnEmptyTouchedFiles_whenDiffNull() {
		String json = "{\"title\":\"test\",\"summary\":\"s\",\"details\":\"d\","
				+ "\"risks\":[],\"suggestedTests\":[],\"touchedFiles\":null,\"analysisNotes\":null}";

		AnalyzeDiffResponse result = mapperService.mapToAnalyzeDiffResponse(
				createChatResponse(json), 100L, null, "req-1", "openai");

		assertThat(result.touchedFiles()).isEmpty();
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldReturnEmptyTouchedFiles_whenDiffBlank() {
		String json = "{\"title\":\"test\",\"summary\":\"s\",\"details\":\"d\","
				+ "\"risks\":[],\"suggestedTests\":[],\"touchedFiles\":null,\"analysisNotes\":null}";

		AnalyzeDiffResponse result = mapperService.mapToAnalyzeDiffResponse(
				createChatResponse(json), 100L, "   ", "req-1", "openai");

		assertThat(result.touchedFiles()).isEmpty();
	}

	@Test
	void mapToAnalyzeDiffResponse_shouldThrowException_whenRiskLevelUnknown() {
		String json = """
				{"title":"test","summary":"s","details":"d",
				 "risks":[{"level":"catastrophic","message":"boom"}],
				 "suggestedTests":[],"touchedFiles":[],"analysisNotes":null}
				""";

		ChatResponse response = createChatResponse(json);

		assertThrows(
				ModelOutputParseException.class, () ->
						mapperService.mapToAnalyzeDiffResponse(
								response, 100L, "diff", "req-10", "openai"
						)
		);
	}

	private ChatResponse createChatResponse(String content) {
		Generation generation = new Generation(new AssistantMessage(content));

		Usage usage = new DefaultUsage(10, 20, 30);

		ChatResponseMetadata metadata = ChatResponseMetadata.builder()
		                                                    .model("test-model")
		                                                    .usage(usage)
		                                                    .build();

		return ChatResponse.builder()
		                   .generations(List.of(generation))
		                   .metadata(metadata)
		                   .build();
	}

	private ChatResponse createChatResponseWithNullUsage(String content) {
		Generation generation = new Generation(new AssistantMessage(content));

		ChatResponseMetadata metadata = ChatResponseMetadata.builder()
		                                                    .model("ollama-model")
		                                                    .usage(null)
		                                                    .build();

		return ChatResponse.builder()
		                   .generations(List.of(generation))
		                   .metadata(metadata)
		                   .build();
	}
}