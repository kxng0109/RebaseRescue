package io.github.kxng0109.rebaserescue.service;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisMetricsTest {

	private SimpleMeterRegistry registry;
	private AnalysisMetrics metrics;

	@BeforeEach
	void setup() {
		registry = new SimpleMeterRegistry();
		metrics = new AnalysisMetrics(registry);
	}

	@Test
	void counters_shouldIncrement() {
		metrics.countCacheHit("openai");
		metrics.countCacheHit("openai");
		metrics.countCacheMiss("openai");

		assertThat(registry.counter("rebaserescue.analysis.cache", "result", "hit", "provider", "openai").count())
				.isEqualTo(2.0);
		assertThat(registry.counter("rebaserescue.analysis.cache", "result", "miss", "provider", "openai").count())
				.isEqualTo(1.0);
	}

	@Test
	void sample_shouldRecordLatency() {
		Timer.Sample sample = metrics.startSample();
		metrics.stopSample(sample, "ollama", "success");

		Timer timer = registry.timer("rebaserescue.analysis.duration", "provider", "ollama", "outcome", "success");
		assertThat(timer.count()).isEqualTo(1);
	}

	@Test
	void stopSample_shouldTagAllOutcomes() {
		metrics.stopSample(metrics.startSample(), "openai", "bulkhead-full");
		metrics.stopSample(metrics.startSample(), "openai", "error");

		assertThat(registry.timer(
				"rebaserescue.analysis.duration",
				"provider", "openai", "outcome", "bulkhead-full"
		).count()).isEqualTo(1);
		assertThat(registry.timer(
				"rebaserescue.analysis.duration",
				"provider", "openai", "outcome", "error"
		).count()).isEqualTo(1);
	}
}
