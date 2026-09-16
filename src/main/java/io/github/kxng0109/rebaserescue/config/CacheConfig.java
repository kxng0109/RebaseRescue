package io.github.kxng0109.rebaserescue.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.kxng0109.rebaserescue.api.dto.AnalyzeDiffResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Diff-hash response cache (content-addressed, bounded, TTL).
 */
@Configuration
@RequiredArgsConstructor
public class CacheConfig {

	private final PrCopilotAnalysisProperties analysisProperties;

	@Bean
	public Cache<String, AnalyzeDiffResponse> analysisCache() {
		int maxSize = analysisProperties.getCacheMaxSize();
		if (maxSize == 0) {
			// Caffeine's documented disable mechanism: size zero evicts immediately.
			// Synchronous executor gives deterministic no-hit semantics instead of
			// the default async-eviction window on ForkJoinPool.commonPool().
			return Caffeine.newBuilder()
			               .maximumSize(0)
			               .executor(Runnable::run)
			               .recordStats()
			               .build();
		}
		return Caffeine.newBuilder()
		               .maximumSize(maxSize)
		               .expireAfterWrite(analysisProperties.getCacheTtl())
		               .recordStats()
		               .build();
	}
}
