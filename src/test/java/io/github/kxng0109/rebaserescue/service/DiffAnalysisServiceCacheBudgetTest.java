package io.github.kxng0109.rebaserescue.service;

import io.github.kxng0109.rebaserescue.api.dto.AnalyzeDiffResponse;
import io.github.kxng0109.rebaserescue.api.dto.RiskItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiffAnalysisServiceCacheBudgetTest {

    @Test
    void estimateResponseChars_shouldSumTextFields() {
        AnalyzeDiffResponse response = AnalyzeDiffResponse.builder()
                .title("abc")
                .summary("de")
                .details("fghi")
                .risks(List.of(new RiskItem("error", "jkl")))
                .suggestedTests(List.of("mn"))
                .touchedFiles(List.of("o"))
                .analysisNotes("pq")
                .rawModelOutput("rs")
                .requestId("t")
                .build();

        assertThat(DiffAnalysisService.estimateResponseChars(response)).isEqualTo(25L);
    }

    @Test
    void estimateResponseChars_shouldTolerateNulls() {
        AnalyzeDiffResponse response = AnalyzeDiffResponse.builder().title("t").build();

        assertThat(DiffAnalysisService.estimateResponseChars(response)).isEqualTo(1L);
    }
}
