package io.github.kxng0109.rebaserescue.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Micrometer telemetry for analysis calls (cache hit/miss, per-provider latency).
 */
@Service
@RequiredArgsConstructor
public class AnalysisMetrics {

	private final MeterRegistry meterRegistry;

	public void countCacheHit(String provider) {
		meterRegistry.counter("rebaserescue.analysis.cache", "result", "hit", "provider", provider).increment();
	}

	public void countCacheMiss(String provider) {
		meterRegistry.counter("rebaserescue.analysis.cache", "result", "miss", "provider", provider).increment();
	}

	public Timer.Sample startSample() {
		return Timer.start(meterRegistry);
	}

	public void stopSample(Timer.Sample sample, String provider, String outcome) {
		sample.stop(meterRegistry.timer(
				"rebaserescue.analysis.duration",
				"provider", provider, "outcome", outcome
		));
	}
}
