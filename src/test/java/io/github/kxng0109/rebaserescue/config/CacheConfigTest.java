package io.github.kxng0109.rebaserescue.config;

import com.github.benmanes.caffeine.cache.Cache;
import io.github.kxng0109.rebaserescue.api.dto.AnalyzeDiffResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheConfigTest {

	@Mock
	private PrCopilotAnalysisProperties analysisProperties;

	private static AnalyzeDiffResponse sampleResponse() {
		return AnalyzeDiffResponse.builder()
		                          .title("t")
		                          .summary("s")
		                          .requestId("req-cache")
		                          .build();
	}

	@Test
	void analysisCache_shouldMissAlways_whenMaxSizeIsZero() {
		when(analysisProperties.getCacheMaxSize()).thenReturn(0);
		Cache<String, AnalyzeDiffResponse> cache = new CacheConfig(analysisProperties).analysisCache();

		cache.put("k", sampleResponse());

		assertThat(cache.getIfPresent("k")).isNull();
	}

	@Test
	void analysisCache_shouldHit_whenMaxSizeIsPositive() {
		when(analysisProperties.getCacheMaxSize()).thenReturn(1000);
		when(analysisProperties.getCacheTtl()).thenReturn(Duration.ofMinutes(30));
		Cache<String, AnalyzeDiffResponse> cache = new CacheConfig(analysisProperties).analysisCache();
		AnalyzeDiffResponse response = sampleResponse();

		cache.put("k", response);

		assertThat(cache.getIfPresent("k")).isSameAs(response);
	}

	@Test
	void analysisCache_shouldRecordStats() {
		when(analysisProperties.getCacheMaxSize()).thenReturn(10);
		when(analysisProperties.getCacheTtl()).thenReturn(Duration.ofMinutes(30));
		Cache<String, AnalyzeDiffResponse> cache = new CacheConfig(analysisProperties).analysisCache();

		cache.getIfPresent("missing");

		assertThat(cache.stats().missCount()).isEqualTo(1);
	}
}
