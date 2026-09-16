package io.github.kxng0109.rebaserescue.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Converts blank {@code fallback-provider} values to {@code null} so empty env vars do not fail enum binding or slip
 * past {@code == null} guards.
 */
@Component
public class AiProviderConverter implements Converter<String, AiProvider> {

	@Override
	public AiProvider convert(String source) {
		if (source == null || source.isBlank()) {
			return null;
		}
		return AiProvider.fromValue(source.trim());
	}
}
