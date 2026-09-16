package io.github.kxng0109.rebaserescue.service;

import io.github.kxng0109.rebaserescue.api.dto.AnalyzeDiffResponse;
import io.github.kxng0109.rebaserescue.api.dto.RiskItem;
import io.github.kxng0109.rebaserescue.config.PrCopilotAnalysisProperties;
import io.github.kxng0109.rebaserescue.config.PrCopilotSarifProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SarifServiceTest {

	@TempDir
	Path tempDir;
	private PrCopilotSarifProperties sarifProperties;
	private PrCopilotAnalysisProperties analysisProperties;
	private SarifService sarifService;

	@BeforeEach
	void setup() {
		sarifProperties = mock(PrCopilotSarifProperties.class);
		analysisProperties = mock(PrCopilotAnalysisProperties.class);
		lenient().when(sarifProperties.getCategory()).thenReturn("rebase-rescue");
		lenient().when(sarifProperties.getSuppressFile()).thenReturn("");
		lenient().when(analysisProperties.getMinLevel()).thenReturn("note");
		sarifService = new SarifService(sarifProperties, analysisProperties);
	}

	private AnalyzeDiffResponse response(RiskItem... risks) {
		return AnalyzeDiffResponse.builder()
		                          .title("t")
		                          .summary("s")
		                          .details("d")
		                          .risks(List.of(risks))
		                          .riskScore(0)
		                          .touchedFiles(List.of("src/Main.java"))
		                          .requestId("req-1")
		                          .build();
	}

	@Test
	@SuppressWarnings("unchecked")
	void toSarif_shouldEmitValidDocument() {
		Map<String, Object> sarif = sarifService.toSarif(
				response(new RiskItem("error", "SQL injection"), new RiskItem("note", "typo")));

		assertThat(sarif.get("version")).isEqualTo("2.1.0");
		assertThat(sarif.get("$schema").toString()).contains("sarif-2.1.0.json");
		List<Map<String, Object>> runs = (List<Map<String, Object>>) sarif.get("runs");
		assertThat(runs).hasSize(1);
		Map<String, Object> run = runs.getFirst();
		assertThat(((Map<String, Object>) run.get("automationDetails")).get("id")).isEqualTo("rebase-rescue");
		List<Map<String, Object>> results = (List<Map<String, Object>>) run.get("results");
		assertThat(results).hasSize(2);
		assertThat(results.getFirst().get("level")).isEqualTo("error");
		assertThat(results.getFirst().get("ruleId").toString()).startsWith("APR-");
		assertThat(((Map<String, Object>) results.getFirst().get("partialFingerprints")))
				.containsKey("primaryLocationLineHash");
		Map<String, Object> tool = (Map<String, Object>) run.get("tool");
		Map<String, Object> driver = (Map<String, Object>) tool.get("driver");
		assertThat(driver.get("name")).isEqualTo("rebase-rescue");
		assertThat((List<Map<String, Object>>) driver.get("rules")).hasSize(2);
	}

	@Test
	@SuppressWarnings("unchecked")
	void toSarif_shouldFilterBelowMinLevel() {
		when(analysisProperties.getMinLevel()).thenReturn("warning");

		Map<String, Object> sarif = sarifService.toSarif(
				response(new RiskItem("error", "boom"), new RiskItem("note", "typo")));

		List<Map<String, Object>> runs = (List<Map<String, Object>>) sarif.get("runs");
		List<Map<String, Object>> results = (List<Map<String, Object>>) runs.getFirst().get("results");
		assertThat(results).hasSize(1);
		assertThat(results.getFirst().get("level")).isEqualTo("error");
	}

	@Test
	@SuppressWarnings("unchecked")
	void toSarif_shouldSuppressMatchingRuleId() throws Exception {
		Map<String, Object> probe = sarifService.toSarif(response(new RiskItem("warning", "slow query")));
		List<Map<String, Object>> runs = (List<Map<String, Object>>) probe.get("runs");
		List<Map<String, Object>> results = (List<Map<String, Object>>) runs.getFirst().get("results");
		String ruleId = results.getFirst().get("ruleId").toString();

		Path suppress = tempDir.resolve("ignore.yml");
		Files.writeString(suppress, "- ruleId: \"" + ruleId + "\"\n  reason: \"accepted\"\n");
		when(sarifProperties.getSuppressFile()).thenReturn(suppress.toString());

		Map<String, Object> sarif = sarifService.toSarif(response(new RiskItem("warning", "slow query")));
		List<Map<String, Object>> runs2 = (List<Map<String, Object>>) sarif.get("runs");
		assertThat((List<Map<String, Object>>) runs2.getFirst().get("results")).isEmpty();
	}

	@Test
	@SuppressWarnings("unchecked")
	void toSarif_shouldIgnoreExpiredSuppressions() throws Exception {
		Path suppress = tempDir.resolve("ignore.yml");
		Files.writeString(suppress, "- ruleId: \"APR-00000000\"\n  expires: \"2000-01-01\"\n");
		when(sarifProperties.getSuppressFile()).thenReturn(suppress.toString());

		Map<String, Object> sarif = sarifService.toSarif(response(new RiskItem("warning", "slow query")));
		List<Map<String, Object>> runs = (List<Map<String, Object>>) sarif.get("runs");
		assertThat((List<Map<String, Object>>) runs.getFirst().get("results")).hasSize(1);
	}

	@Test
	void ruleId_shouldBeStableForSameRisk() {
		RiskItem risk = new RiskItem("error", "SQL injection");
		assertThat(SarifService.ruleId(risk)).isEqualTo(SarifService.ruleId(risk));
	}

	@Test
	@SuppressWarnings("unchecked")
	void toSarif_shouldUseNullMinLevelAsNote() {
		when(analysisProperties.getMinLevel()).thenReturn(null);

		Map<String, Object> sarif = sarifService.toSarif(response(new RiskItem("note", "typo")));

		List<Map<String, Object>> runs = (List<Map<String, Object>>) sarif.get("runs");
		assertThat((List<Map<String, Object>>) runs.getFirst().get("results")).hasSize(1);
	}

	@Test
	@SuppressWarnings("unchecked")
	void toSarif_shouldFallBackToDiffUri_whenTouchedFilesNull() {
		AnalyzeDiffResponse withoutFiles = AnalyzeDiffResponse.builder()
		                                                      .title("t")
		                                                      .summary("s")
		                                                      .risks(List.of(new RiskItem("error", "boom")))
		                                                      .requestId("req-1")
		                                                      .build();

		Map<String, Object> sarif = sarifService.toSarif(withoutFiles);

		List<Map<String, Object>> runs = (List<Map<String, Object>>) sarif.get("runs");
		List<Map<String, Object>> results = (List<Map<String, Object>>) runs.getFirst().get("results");
		assertThat(results).hasSize(1);
		List<Map<String, Object>> locations =
				(List<Map<String, Object>>) results.getFirst().get("locations");
		Map<String, Object> physical =
				(Map<String, Object>) locations.getFirst().get("physicalLocation");
		assertThat(((Map<String, Object>) physical.get("artifactLocation")).get("uri"))
				.isEqualTo("diff");
	}

	@Test
	@SuppressWarnings("unchecked")
	void toSarif_shouldKeepUnmatchedSuppressions() throws Exception {
		Path suppress = tempDir.resolve("other.yml");
		Files.writeString(suppress, "- ruleId: \"APR-00000000\"\n  expires: \"2099-01-01\"\n");
		when(sarifProperties.getSuppressFile()).thenReturn(suppress.toString());

		Map<String, Object> sarif = sarifService.toSarif(response(new RiskItem("warning", "slow query")));

		List<Map<String, Object>> runs = (List<Map<String, Object>>) sarif.get("runs");
		assertThat((List<Map<String, Object>>) runs.getFirst().get("results")).hasSize(1);
	}

	@Test
	@SuppressWarnings("unchecked")
	void toSarif_shouldEmitEmptyResults_whenRisksNull() {
		AnalyzeDiffResponse withoutRisks = AnalyzeDiffResponse.builder()
		                                                      .title("t")
		                                                      .summary("s")
		                                                      .touchedFiles(List.of("src/Main.java"))
		                                                      .requestId("req-1")
		                                                      .build();

		Map<String, Object> sarif = sarifService.toSarif(withoutRisks);

		List<Map<String, Object>> runs = (List<Map<String, Object>>) sarif.get("runs");
		assertThat((List<Map<String, Object>>) runs.getFirst().get("results")).isEmpty();
	}

	@Test
	@SuppressWarnings("unchecked")
	void toSarif_shouldUseDiffUri_whenNoTouchedFiles() {
		AnalyzeDiffResponse withoutFiles = AnalyzeDiffResponse.builder()
		                                                      .title("t")
		                                                      .summary("s")
		                                                      .risks(List.of(new RiskItem("error", "boom")))
		                                                      .touchedFiles(List.of())
		                                                      .requestId("req-1")
		                                                      .build();

		Map<String, Object> sarif = sarifService.toSarif(withoutFiles);

		List<Map<String, Object>> runs = (List<Map<String, Object>>) sarif.get("runs");
		List<Map<String, Object>> results = (List<Map<String, Object>>) runs.getFirst().get("results");
		List<Map<String, Object>> locations =
				(List<Map<String, Object>>) results.getFirst().get("locations");
		Map<String, Object> physical =
				(Map<String, Object>) locations.getFirst().get("physicalLocation");
		Map<String, Object> artifact =
				(Map<String, Object>) physical.get("artifactLocation");
		assertThat(artifact.get("uri")).isEqualTo("diff");
	}

	@Test
	void meetsThreshold_shouldCompareRanks() {
		assertThat(SarifService.meetsThreshold("error", "warning")).isTrue();
		assertThat(SarifService.meetsThreshold("note", "error")).isFalse();
		assertThat(SarifService.meetsThreshold("warning", "warning")).isTrue();
		assertThat(SarifService.meetsThreshold(null, "note")).isFalse();
		assertThat(SarifService.meetsThreshold("error", null)).isTrue();
	}

	@Test
	@SuppressWarnings("unchecked")
	void toSarif_shouldDeduplicateRules() {
		Map<String, Object> sarif = sarifService.toSarif(
				response(new RiskItem("error", "same"), new RiskItem("error", "same")));

		List<Map<String, Object>> runs = (List<Map<String, Object>>) sarif.get("runs");
		Map<String, Object> run = runs.getFirst();
		Map<String, Object> tool = (Map<String, Object>) run.get("tool");
		Map<String, Object> driver = (Map<String, Object>) tool.get("driver");
		assertThat((List<Map<String, Object>>) driver.get("rules")).hasSize(1);
		assertThat((List<Map<String, Object>>) run.get("results")).hasSize(2);
	}

	@Test
	@SuppressWarnings("unchecked")
	void toSarif_shouldIgnoreMissingSuppressFile() {
		when(sarifProperties.getSuppressFile()).thenReturn(tempDir.resolve("nope.yml").toString());

		Map<String, Object> sarif = sarifService.toSarif(response(new RiskItem("error", "boom")));

		List<Map<String, Object>> runs = (List<Map<String, Object>>) sarif.get("runs");
		assertThat((List<Map<String, Object>>) runs.getFirst().get("results")).hasSize(1);
	}

	@Test
	@SuppressWarnings("unchecked")
	void toSarif_shouldIgnoreMalformedSuppressFile() throws Exception {
		Path suppress = tempDir.resolve("bad.yml");
		Files.writeString(suppress, "not: [valid, yaml\n");
		when(sarifProperties.getSuppressFile()).thenReturn(suppress.toString());

		Map<String, Object> sarif = sarifService.toSarif(response(new RiskItem("error", "boom")));

		List<Map<String, Object>> runs = (List<Map<String, Object>>) sarif.get("runs");
		assertThat((List<Map<String, Object>>) runs.getFirst().get("results")).hasSize(1);
	}

	@Test
	@SuppressWarnings("unchecked")
	void toSarif_shouldIgnoreNonListSuppressFile() throws Exception {
		Path suppress = tempDir.resolve("map.yml");
		Files.writeString(suppress, "key: value\n");
		when(sarifProperties.getSuppressFile()).thenReturn(suppress.toString());

		Map<String, Object> sarif = sarifService.toSarif(response(new RiskItem("error", "boom")));

		List<Map<String, Object>> runs = (List<Map<String, Object>>) sarif.get("runs");
		assertThat((List<Map<String, Object>>) runs.getFirst().get("results")).hasSize(1);
	}

	@Test
	@SuppressWarnings("unchecked")
	void toSarif_shouldSuppressByFingerprint_whenActive() throws Exception {
		RiskItem risk = new RiskItem("warning", "slow query");
		String ruleId = SarifService.ruleId(risk);
		String fingerprint = SarifService.fingerprint(ruleId, "src/Main.java");
		Path suppress = tempDir.resolve("fp.yml");
		Files.writeString(suppress, "- fingerprint: \"" + fingerprint + "\"\n  expires: \"2099-01-01\"\n");
		when(sarifProperties.getSuppressFile()).thenReturn(suppress.toString());

		Map<String, Object> sarif = sarifService.toSarif(response(risk));

		List<Map<String, Object>> runs = (List<Map<String, Object>>) sarif.get("runs");
		assertThat((List<Map<String, Object>>) runs.getFirst().get("results")).isEmpty();
	}
}
