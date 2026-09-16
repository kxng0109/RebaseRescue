package io.github.kxng0109.rebaserescue.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Represents the result of analyzing a code change diff.
 *
 * @param title          a brief summary of the analysis, must not be blank
 * @param summary        a concise description of the changes, must not be blank
 * @param details        detailed information about the changes, may be {@code null}
 * @param risks          potential risks or issues introduced by the changes, may be an empty list
 * @param suggestedTests recommended tests to validate the changes, may be an empty list
 * @param touchedFiles   files that were modified by the changes, must not be empty
 * @param analysisNotes  additional notes or comments about the analysis, may be {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelAnalyzeDiffResult(
		String title,
		String summary,
		String details,
		List<RiskItem> risks,
		List<String> suggestedTests,
		List<String> touchedFiles,
		String analysisNotes
) {
}
