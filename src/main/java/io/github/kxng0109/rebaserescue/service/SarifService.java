package io.github.kxng0109.rebaserescue.service;

import io.github.kxng0109.rebaserescue.api.dto.AnalyzeDiffResponse;
import io.github.kxng0109.rebaserescue.api.dto.RiskItem;
import io.github.kxng0109.rebaserescue.config.AppInfo;
import io.github.kxng0109.rebaserescue.config.PrCopilotAnalysisProperties;
import io.github.kxng0109.rebaserescue.config.PrCopilotSarifProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.*;

/**
 * Builds SARIF 2.1.0 reports from analysis responses.
 *
 * <p>Targets GitHub Code Scanning first ({@code runAutomationDetails.id} category +
 * {@code primaryLocationLineHash} fingerprints); the same document satisfies SonarQube native SARIF mandatory fields.
 * Findings below {@code prcopilot.analysis.min-level} are filtered at emit time, and entries in the repo-local suppress
 * file are excluded.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SarifService {

	static final String SARIF_VERSION = "2.1.0";
	static final String SARIF_SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json";
	static final String TOOL_NAME = AppInfo.NAME;

	private final PrCopilotSarifProperties sarifProperties;
	private final PrCopilotAnalysisProperties analysisProperties;

	/**
	 * Converts an analysis response to a SARIF 2.1.0 document model.
	 *
	 * @param response the analysis response, must not be {@code null}
	 * @return SARIF document as nested maps (serializable to JSON), never {@code null}
	 */
	public Map<String, Object> toSarif(AnalyzeDiffResponse response) {
		List<Map<String, Object>> rules = new ArrayList<>();
		List<Map<String, Object>> results = new ArrayList<>();
		List<RiskItem> risks = response.risks() == null ? List.of() : response.risks();
		String uri = firstLocation(response);

		List<SuppressEntry> suppressions = loadSuppressions();

		for (RiskItem risk : risks) {
			if (!meetsThreshold(risk.level())) {
				continue;
			}
			String ruleId = ruleId(risk);
			String fingerprint = fingerprint(ruleId, uri);
			if (isSuppressed(suppressions, ruleId, fingerprint)) {
				log.debug("SARIF finding suppressed: {}", ruleId);
				continue;
			}
			int ruleIndex = findOrAddRule(rules, ruleId, risk.level());
			results.add(result(ruleId, ruleIndex, risk, uri, fingerprint));
		}

		Map<String, Object> driver = new LinkedHashMap<>();
		driver.put("name", AppInfo.NAME);
		driver.put("version", AppInfo.VERSION);
		driver.put("informationUri", "https://github.com/kxng0109/RebaseRescue");
		driver.put("rules", rules);

		Map<String, Object> automationDetails = new LinkedHashMap<>();
		automationDetails.put("id", sarifProperties.getCategory());

		Map<String, Object> run = new LinkedHashMap<>();
		run.put("tool", Map.of("driver", driver));
		run.put("automationDetails", automationDetails);
		run.put("results", results);

		Map<String, Object> sarif = new LinkedHashMap<>();
		sarif.put("$schema", SARIF_SCHEMA);
		sarif.put("version", SARIF_VERSION);
		sarif.put("runs", List.of(run));
		return sarif;
	}

	private String firstLocation(AnalyzeDiffResponse response) {
		if (response.touchedFiles() != null && !response.touchedFiles().isEmpty()) {
			return response.touchedFiles().get(0);
		}
		return "diff";
	}

	static boolean meetsThreshold(String level, String minLevel) {
		return rank(level) >= rank(minLevel);
	}

	private boolean meetsThreshold(String level) {
		String min = analysisProperties.getMinLevel();
		return meetsThreshold(level, min == null ? "note" : min);
	}

	private static int rank(String level) {
		if (level == null) {
			return 0;
		}
		return switch (level.trim().toLowerCase()) {
			case "error" -> 3;
			case "warning" -> 2;
			default -> 1;
		};
	}

	static String ruleId(RiskItem risk) {
		return "APR-" + sha8(risk.level() + "|" + risk.message());
	}

	static String fingerprint(String ruleId, String uri) {
		return sha16(ruleId + "|" + uri);
	}

	private int findOrAddRule(List<Map<String, Object>> rules, String ruleId, String level) {
		for (int i = 0; i < rules.size(); i++) {
			if (ruleId.equals(rules.get(i).get("id"))) {
				return i;
			}
		}
		Map<String, Object> rule = new LinkedHashMap<>();
		rule.put("id", ruleId);
		rule.put("name", ruleId);
		rule.put("shortDescription", Map.of("text", "AI-identified " + level + " risk"));
		rule.put("defaultConfiguration", Map.of("level", level));
		rule.put("properties", Map.of("precision", "high"));
		rules.add(rule);
		return rules.size() - 1;
	}

	private Map<String, Object> result(
			String ruleId, int ruleIndex, RiskItem risk, String uri, String fingerprint) {
		Map<String, Object> region = new LinkedHashMap<>();
		region.put("startLine", 1);

		Map<String, Object> physicalLocation = new LinkedHashMap<>();
		physicalLocation.put("artifactLocation", Map.of("uri", uri));
		physicalLocation.put("region", region);

		Map<String, Object> fingerprints = new LinkedHashMap<>();
		fingerprints.put("primaryLocationLineHash", fingerprint);

		Map<String, Object> location = new LinkedHashMap<>();
		location.put("physicalLocation", physicalLocation);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("ruleId", ruleId);
		result.put("ruleIndex", ruleIndex);
		result.put("level", risk.level());
		result.put("message", Map.of("text", risk.message()));
		result.put("locations", List.of(location));
		result.put("partialFingerprints", fingerprints);
		return result;
	}

	private List<SuppressEntry> loadSuppressions() {
		String path = sarifProperties.getSuppressFile();
		if (path == null || path.isBlank()) {
			return List.of();
		}
		Path file = Path.of(path);
		if (!Files.isRegularFile(file)) {
			return List.of();
		}
		try (InputStream in = Files.newInputStream(file)) {
			Object loaded = new Yaml().load(in);
			if (!(loaded instanceof List<?> entries)) {
				log.warn("Suppress file {} is not a YAML list, ignoring", path);
				return List.of();
			}
			List<SuppressEntry> result = new ArrayList<>();
			for (Object entry : entries) {
				if (entry instanceof Map<?, ?> map) {
					Object expires = map.get("expires");
					if (expires != null && !expires.toString().isBlank()) {
						LocalDate until = LocalDate.parse(expires.toString().trim());
						if (until.isBefore(LocalDate.now())) {
							continue;
						}
					}
					Object ruleId = map.get("ruleId");
					Object fingerprint = map.get("fingerprint");
					result.add(new SuppressEntry(
							ruleId == null ? null : ruleId.toString(),
							fingerprint == null ? null : fingerprint.toString()
					));
				}
			}
			return result;
		} catch (IOException | RuntimeException e) {
			log.warn("Could not read suppress file {}, ignoring: {}", path, e.getMessage());
			return List.of();
		}
	}

	private boolean isSuppressed(List<SuppressEntry> suppressions, String ruleId, String fingerprint) {
		for (SuppressEntry entry : suppressions) {
			if (ruleId.equals(entry.ruleId()) || fingerprint.equals(entry.fingerprint())) {
				return true;
			}
		}
		return false;
	}

	private record SuppressEntry(String ruleId, String fingerprint) {
	}

	private static String sha8(String input) {
		return sha(input).substring(0, 8);
	}

	private static String sha16(String input) {
		return sha(input).substring(0, 16);
	}

	private static String sha(String input) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
			                             .digest(input.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			return Integer.toHexString(input.hashCode());
		}
	}
}
