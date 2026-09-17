package io.github.kxng0109.rebaserescue.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptBuilderServiceTest {

	private PromptBuilderService service;

	@BeforeEach
	void setup() {
		service = new PromptBuilderService();
		ReflectionTestUtils.setField(
				service, "systemPromptResource",
				new FileSystemResource("src/main/resources/prompts/system-prompt.txt")
		);
		service.loadSystemPromptAtStartup();
	}

	@Test
	void systemMessage_shouldSubstituteVariablesAndKeepLiteralBraces() {
		Prompt prompt = service.buildDiffAnalysisPrompt(
				"java", "conventional-commits", "diff --git a/F.java", 300, "req-1");

		String system = prompt.getInstructions().get(0).getText();

		assertThat(system).contains("- language: java");
		assertThat(system).contains("- style: conventional-commits");
		assertThat(system).doesNotContain("{language}", "{style}");
		assertThat(system).doesNotContain("\\{", "\\}");
		assertThat(system).contains("\"title\"");
		assertThat(system).contains("{\"level\": \"error|warning|note\"");
	}

	@Test
	void userMessage_shouldContainDiffAndHints() {
		Prompt prompt = service.buildDiffAnalysisPrompt(
				"java", "conventional-commits", "DIFFBODY", null, null);

		String user = prompt.getInstructions().get(1).getText();

		assertThat(user).contains("DIFFBODY");
		assertThat(user).contains("language: java");
		assertThat(user).contains("style: conventional-commits");
		assertThat(user).doesNotContain("maxSummaryLength");
		assertThat(user).doesNotContain("requestId");
	}

	@Test
	void userMessage_shouldIncludeOptionalHints_whenPresent() {
		Prompt prompt = service.buildDiffAnalysisPrompt(
				"java", "conventional-commits", "DIFFBODY", 300, "req-9");

		String user = prompt.getInstructions().get(1).getText();

		assertThat(user).contains("maxSummaryLength: 300");
		assertThat(user).contains("requestId: req-9");
	}

	@Test
	void userMessage_shouldSkipBlankRequestId() {
		Prompt prompt = service.buildDiffAnalysisPrompt(
				"java", "conventional-commits", "DIFFBODY", null, "   ");

		String user = prompt.getInstructions().get(1).getText();

		assertThat(user).doesNotContain("requestId");
	}

	@Test
	void templateHash_shouldBeStableHex() {
		assertThat(service.templateHash()).isEqualTo(service.templateHash());
		assertThat(service.templateHash()).hasSize(16);
	}

	@Test
	void loadSystemPromptAtStartup_shouldThrow_whenResourceMissing() {
		PromptBuilderService broken = new PromptBuilderService();
		ReflectionTestUtils.setField(
				broken, "systemPromptResource",
				new FileSystemResource("no-such-prompt-xyz.txt")
		);

		assertThatThrownBy(broken::loadSystemPromptAtStartup)
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining("Could not load system prompt");
	}
}
