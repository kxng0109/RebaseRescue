package io.github.kxng0109.rebaserescue.service;

import io.github.kxng0109.rebaserescue.error.BlockedDiffException;
import io.github.kxng0109.rebaserescue.service.SecretScanService.ScanResult;
import io.github.kxng0109.rebaserescue.service.SecretScanService.Verdict;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * In-process input guardrail for untrusted diffs.
 *
 * <p>Runs first ({@link Ordered#HIGHEST_PRECEDENCE}) on both call and stream paths:
 * size cap, instruction-override denylist, then {@link SecretScanService}. BLOCK verdicts hard-fail with
 * {@link BlockedDiffException} (no LLM cost); REDACT verdicts rewrite user messages with the redacted text and
 * continue.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DiffGuardrailAdvisor implements CallAdvisor, StreamAdvisor {

	static final int MAX_PROMPT_CHARS_HEADROOM = 30_000;

	private static final List<Pattern> DENYLIST = List.of(
			Pattern.compile("ignore\\s+(all\\s+)?previous\\s+instructions", Pattern.CASE_INSENSITIVE),
			Pattern.compile("disregard\\s+(all\\s+)?(prior|previous)\\s+instructions", Pattern.CASE_INSENSITIVE),
			Pattern.compile("(reveal|output|show|print)\\s+(your\\s+)?system\\s+prompt", Pattern.CASE_INSENSITIVE),
			Pattern.compile("(send|post|exfiltrat\\w*)\\s+\\S*\\s*to\\s+https?://", Pattern.CASE_INSENSITIVE)
	);

	private final SecretScanService secretScanService;
	private final io.github.kxng0109.rebaserescue.config.PrCopilotAnalysisProperties analysisProperties;

	@Override
	public String getName() {
		return "DiffGuardrailAdvisor";
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		return chain.nextCall(guard(request));
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
		ChatClientRequest guarded;
		try {
			guarded = guard(request);
		} catch (RuntimeException e) {
			return Flux.error(e);
		}
		return chain.nextStream(guarded);
	}

	/**
	 * Concatenates user-message texts only. The instruction-override denylist must not run over the system prompt:
	 * template wording is trusted and must never trigger a false positive that blocks every analysis.
	 *
	 * @param prompt the prompt to inspect, must not be {@code null}
	 * @return the joined user texts, never {@code null}
	 */
	private static String userText(Prompt prompt) {
		StringBuilder haystack = new StringBuilder();
		for (Message message : prompt.getInstructions()) {
			if (message instanceof UserMessage userMessage && userMessage.getText() != null) {
				haystack.append(userMessage.getText()).append('\n');
			}
		}
		return haystack.toString();
	}

	private ChatClientRequest guard(ChatClientRequest request) {
		Prompt prompt = request.prompt();
		String contents = prompt.getContents();
		int cap = analysisProperties.getMaxDiffChars() + MAX_PROMPT_CHARS_HEADROOM;
		if (contents != null && contents.length() > cap) {
			throw new io.github.kxng0109.rebaserescue.error.DiffTooLargeException(
					String.format("Prompt exceeded maximum allowed size of %d characters", cap));
		}
		String haystack = userText(prompt).toLowerCase(Locale.ROOT);
		for (Pattern denied : DENYLIST) {
			if (denied.matcher(haystack).find()) {
				log.warn("Guardrail blocked prompt-injection pattern");
				throw new BlockedDiffException(
						"Blocked: prompt contains an instruction-override attempt. Remove it and retry.");
			}
		}
		List<Message> messages = new ArrayList<>();
		boolean redacted = false;
		for (Message message : prompt.getInstructions()) {
			if (message instanceof UserMessage userMessage) {
				ScanResult scan = secretScanService.scan(userMessage.getText());
				if (scan.verdict() == Verdict.BLOCKED) {
					throw new BlockedDiffException(scan.reason());
				}
				if (scan.verdict() == Verdict.REDACTED) {
					redacted = true;
					messages.add(new UserMessage(scan.text()));
					continue;
				}
			}
			messages.add(message);
		}
		if (!redacted) {
			return request;
		}
		log.warn("Guardrail redacted suspected secret(s) from prompt");
		Prompt redactedPrompt = new Prompt(messages);
		return request.mutate().prompt(redactedPrompt).build();
	}
}
