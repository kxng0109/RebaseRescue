package io.github.kxng0109.rebaserescue.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Defines supported AI providers that can be used for interaction with AI systems.
 * <p>
 * Each constant represents a specific provider and its corresponding string identifier.
 */
@Getter
@RequiredArgsConstructor
public enum AiProvider {
	OPENAI("openai"),
	ANTHROPIC("anthropic"),
	GEMINI("gemini"),
	OLLAMA("ollama");

	private final String value;

	/**
	 * Returns the {@code AiProvider} corresponding to the given string value.
	 *
	 * @param value the string representation of the provider; must not be {@code null} or empty
	 * @return the matching {@code AiProvider}, never {@code null}
	 * @throws IllegalArgumentException if no matching {@code AiProvider} is found
	 */
	public static AiProvider fromValue(String value) {
		for (AiProvider a : AiProvider.values()) {
			if (a.value.equalsIgnoreCase(value)) {
				return a;
			}
		}

		throw new IllegalArgumentException(String.format("Unknown AI provider: %s.", value));
	}
}
