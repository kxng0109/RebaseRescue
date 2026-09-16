package io.github.kxng0109.rebaserescue.cli;

import io.github.kxng0109.rebaserescue.api.dto.AiCallMetadata;
import io.github.kxng0109.rebaserescue.api.dto.AnalyzeDiffResponse;
import io.github.kxng0109.rebaserescue.config.AppInfo;
import io.github.kxng0109.rebaserescue.service.DiffAnalysisService;
import io.github.kxng0109.rebaserescue.service.GitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AnalyzeCommandTest {
	@Mock
	private GitService gitService;

	@Mock
	private DiffAnalysisService diffAnalysisService;

	private AnalyzeCommand analyzeCommand;
	private CommandLine commandLine;
	private StringWriter stdout;
	private StringWriter stderr;

	@BeforeEach
	void setUp() {
		analyzeCommand = new AnalyzeCommand(gitService, diffAnalysisService, JsonMapper.builder().build());
		commandLine = new CommandLine(analyzeCommand);

		stdout = new StringWriter();
		stderr = new StringWriter();
		commandLine.setOut(new PrintWriter(stdout));
		commandLine.setErr(new PrintWriter(stderr));
	}

	@Test
	void help_shouldDisplayUsageInfo() {
		int exitCode = commandLine.execute("--help");

		assertEquals(0, exitCode);
		String output = stdout.toString();
		assertTrue(output.contains("analyze"));
		assertTrue(output.contains("--base"));
		assertTrue(output.contains("--staged"));
	}

	@Test
	void execute_notInGitRepo_shouldReturnError() {
		when(gitService.isGitRepository()).thenReturn(false);

		int exitCode = commandLine.execute();

		assertEquals(1, exitCode);
		assertTrue(stderr.toString().contains("Not a git repository"));
	}

	@Test
	void execute_noChanges_shouldReturnZeroWithMessage() {
		when(gitService.isGitRepository()).thenReturn(true);
		when(gitService.hasStagedChanges()).thenReturn(false);
		when(gitService.branchExists("main")).thenReturn(false);
		when(gitService.branchExists("master")).thenReturn(false);
		when(gitService.branchExists("develop")).thenReturn(false);

		int exitCode = commandLine.execute();

		assertEquals(0, exitCode);
		assertTrue(stdout.toString().contains("No changes detected"));
	}

	@Test
	void execute_withStagedChanges_shouldAnalyze() {
		when(gitService.isGitRepository()).thenReturn(true);
		when(gitService.getStagedDiff()).thenReturn("diff --git a/file.txt");
		when(diffAnalysisService.analyzeDiff(any())).thenReturn(mockResponse());

		int exitCode = commandLine.execute("--staged");

		assertEquals(0, exitCode);
		verify(gitService).getStagedDiff();
		verify(diffAnalysisService).analyzeDiff(any());
	}

	@Test
	void execute_withBaseBranch_shouldCompareAgainstBranch() {
		when(gitService.isGitRepository()).thenReturn(true);
		when(gitService.branchExists("main")).thenReturn(true);
		when(gitService.getDiffAgainstBranch("main")).thenReturn("diff content");
		when(diffAnalysisService.analyzeDiff(any())).thenReturn(mockResponse());

		int exitCode = commandLine.execute("--base", "main");

		assertEquals(0, exitCode);
		verify(gitService).getDiffAgainstBranch("main");
	}

	@Test
	void execute_withNonExistentBranch_shouldReturnError() {
		when(gitService.isGitRepository()).thenReturn(true);
		when(gitService.branchExists("non-existent")).thenReturn(false);

		int exitCode = commandLine.execute("--base", "non-existent");

		assertEquals(1, exitCode);
		assertTrue(stderr.toString().contains("does not exist"));
	}

	@Test
	void execute_withUncommittedFlag_shouldGetUncommittedDiff() {
		when(gitService.isGitRepository()).thenReturn(true);
		when(gitService.getUncommittedDiff()).thenReturn("uncommitted diff");
		when(diffAnalysisService.analyzeDiff(any())).thenReturn(mockResponse());

		int exitCode = commandLine.execute("--uncommitted");

		assertEquals(0, exitCode);
		verify(gitService).getUncommittedDiff();
	}

	@Test
	void execute_withJsonFormat_shouldOutputJson() {
		when(gitService.isGitRepository()).thenReturn(true);
		when(gitService.hasStagedChanges()).thenReturn(true);
		when(gitService.getStagedDiff()).thenReturn("diff");
		when(diffAnalysisService.analyzeDiff(any())).thenReturn(mockResponse());

		int exitCode = commandLine.execute("--format", "json");

		assertEquals(0, exitCode);
		String output = stdout.toString();
		assertTrue(output.contains("\"title\""));
		assertTrue(output.contains("\"summary\""));
	}

	@Test
	void execute_withCompactFormat_shouldOutputCompact() {
		when(gitService.isGitRepository()).thenReturn(true);
		when(gitService.hasStagedChanges()).thenReturn(true);
		when(gitService.getStagedDiff()).thenReturn("diff");
		when(diffAnalysisService.analyzeDiff(any())).thenReturn(mockResponse());

		int exitCode = commandLine.execute("--format", "compact");

		assertEquals(0, exitCode);
		String output = stdout.toString();
		assertTrue(output.contains("TITLE: "));
		assertTrue(output.contains("SUMMARY:"));
	}

	@Test
	void execute_withQuietFlag_shouldSuppressProgressMessages() {
		when(gitService.isGitRepository()).thenReturn(true);
		when(gitService.hasStagedChanges()).thenReturn(true);
		when(gitService.getStagedDiff()).thenReturn("diff");
		when(diffAnalysisService.analyzeDiff(any())).thenReturn(mockResponse());

		int exitCode = commandLine.execute("--quiet", "--format", "json");

		assertEquals(0, exitCode);
		String output = stdout.toString();
		// Should NOT contain progress messages
		assertFalse(output.contains("Analyzing"));
	}

	@Test
	void execute_autoDetect_shouldTryMainFirst() {
		when(gitService.isGitRepository()).thenReturn(true);
		when(gitService.hasStagedChanges()).thenReturn(false);
		when(gitService.branchExists("main")).thenReturn(true);
		when(gitService.getDiffAgainstBranch("main")).thenReturn("diff from main");
		when(diffAnalysisService.analyzeDiff(any())).thenReturn(mockResponse());

		int exitCode = commandLine.execute();

		assertEquals(0, exitCode);
		verify(gitService).getDiffAgainstBranch("main");
	}

	@Test
	void execute_autoDetect_shouldFallbackToMaster() {
		when(gitService.isGitRepository()).thenReturn(true);
		when(gitService.hasStagedChanges()).thenReturn(false);
		when(gitService.branchExists("main")).thenReturn(false);
		when(gitService.branchExists("master")).thenReturn(true);
		when(gitService.getDiffAgainstBranch("master")).thenReturn("diff from master");
		when(diffAnalysisService.analyzeDiff(any())).thenReturn(mockResponse());

		int exitCode = commandLine.execute();

		assertEquals(0, exitCode);
		verify(gitService).getDiffAgainstBranch("master");
	}

	@Test
	void execute_shouldReturnError_whenGitFails() {
		when(gitService.isGitRepository()).thenReturn(true);
		when(gitService.hasStagedChanges()).thenReturn(true);
		when(gitService.getStagedDiff()).thenThrow(new RuntimeException("git exploded"));

		int exitCode = commandLine.execute();

		assertEquals(1, exitCode);
		assertTrue(stderr.toString().contains("git exploded"));
	}

	@Test
	void execute_quiet_shouldSuppressProgressMessages() {
		when(gitService.isGitRepository()).thenReturn(true);
		when(gitService.hasStagedChanges()).thenReturn(true);
		when(gitService.getStagedDiff()).thenReturn("diff --git a/file.txt");
		when(diffAnalysisService.analyzeDiff(any())).thenReturn(mockResponse());

		int exitCode = commandLine.execute("--quiet");

		assertEquals(0, exitCode);
		assertFalse(stdout.toString().contains("Analyzing"));
		assertTrue(stdout.toString().contains("feat:  add new feature"));
	}

	@Test
	void execute_compact_shouldPrintHumanReadableResult() {
		when(gitService.isGitRepository()).thenReturn(true);
		when(gitService.hasStagedChanges()).thenReturn(true);
		when(gitService.getStagedDiff()).thenReturn("diff --git a/file.txt");
		when(diffAnalysisService.analyzeDiff(any())).thenReturn(mockResponse());

		int exitCode = commandLine.execute("--format", "compact");

		assertEquals(0, exitCode);
		assertTrue(stdout.toString().contains("TITLE:"));
		assertTrue(stdout.toString().contains("SUMMARY:"));
	}

	@Test
	void execute_uncommitted_shouldAnalyzeAllUncommittedChanges() {
		when(gitService.isGitRepository()).thenReturn(true);
		when(gitService.getUncommittedDiff()).thenReturn("diff content");
		when(diffAnalysisService.analyzeDiff(any())).thenReturn(mockResponse());

		int exitCode = commandLine.execute("--uncommitted");

		assertEquals(0, exitCode);
		verify(gitService).getUncommittedDiff();
		verify(diffAnalysisService).analyzeDiff(any());
	}

	@Test
	void execute_version_shouldPrintVersion() {
		int exitCode = commandLine.execute("--version");

		assertEquals(0, exitCode);
		assertTrue(stdout.toString().contains(AppInfo.NAME + " " + AppInfo.VERSION));
	}

	@Test
	void execute_shouldDetectDevelopBranch_whenNoStagedChanges() {
		when(gitService.isGitRepository()).thenReturn(true);
		when(gitService.hasStagedChanges()).thenReturn(false);
		when(gitService.branchExists("main")).thenReturn(false);
		when(gitService.branchExists("master")).thenReturn(false);
		when(gitService.branchExists("develop")).thenReturn(true);
		when(gitService.getDiffAgainstBranch("develop")).thenReturn("develop diff");
		when(diffAnalysisService.analyzeDiff(any())).thenReturn(mockResponse());

		int exitCode = commandLine.execute();

		assertEquals(0, exitCode);
		verify(gitService).getDiffAgainstBranch("develop");
	}

	@Test
	void execute_blankBase_shouldFallThroughToDetection() {
		when(gitService.isGitRepository()).thenReturn(true);
		when(gitService.hasStagedChanges()).thenReturn(false);
		when(gitService.branchExists("main")).thenReturn(false);
		when(gitService.branchExists("master")).thenReturn(false);
		when(gitService.branchExists("develop")).thenReturn(false);

		int exitCode = commandLine.execute("--base", "  ");

		assertEquals(0, exitCode);
		assertTrue(stdout.toString().contains("No changes detected"));
	}

	@Test
	void execute_shouldTryNextBranch_whenDiffBlank() {
		when(gitService.isGitRepository()).thenReturn(true);
		when(gitService.hasStagedChanges()).thenReturn(false);
		when(gitService.branchExists("main")).thenReturn(true);
		when(gitService.getDiffAgainstBranch("main")).thenReturn("   ");
		when(gitService.branchExists("master")).thenReturn(true);
		when(gitService.getDiffAgainstBranch("master")).thenReturn("master diff");
		when(diffAnalysisService.analyzeDiff(any())).thenReturn(mockResponse());

		int exitCode = commandLine.execute();

		assertEquals(0, exitCode);
		verify(gitService).getDiffAgainstBranch("master");
	}

	@Test
	void execute_shouldTryNextBranch_whenDiffThrows() {
		when(gitService.isGitRepository()).thenReturn(true);
		when(gitService.hasStagedChanges()).thenReturn(false);
		when(gitService.branchExists("main")).thenReturn(true);
		when(gitService.getDiffAgainstBranch("main")).thenThrow(new RuntimeException("gone"));
		when(gitService.branchExists("master")).thenReturn(false);
		when(gitService.branchExists("develop")).thenReturn(false);

		int exitCode = commandLine.execute();

		assertEquals(0, exitCode);
		assertTrue(stdout.toString().contains("No changes detected"));
	}

	@Test
	void execute_compact_shouldSkipFiles_whenTouchedFilesNull() {
		when(gitService.isGitRepository()).thenReturn(true);
		when(gitService.hasStagedChanges()).thenReturn(true);
		when(gitService.getStagedDiff()).thenReturn("diff");
		AnalyzeDiffResponse withoutFiles = AnalyzeDiffResponse.builder()
		                                                      .title("t")
		                                                      .summary("s")
		                                                      .details("d")
		                                                      .build();
		when(diffAnalysisService.analyzeDiff(any())).thenReturn(withoutFiles);

		int exitCode = commandLine.execute("--format", "compact");

		assertEquals(0, exitCode);
		assertFalse(stdout.toString().contains("FILES:"));
	}

	@Test
	void execute_compact_shouldSkipFiles_whenTouchedFilesEmpty() {
		when(gitService.isGitRepository()).thenReturn(true);
		when(gitService.hasStagedChanges()).thenReturn(true);
		when(gitService.getStagedDiff()).thenReturn("diff");
		AnalyzeDiffResponse withoutFiles = AnalyzeDiffResponse.builder()
		                                                      .title("t")
		                                                      .summary("s")
		                                                      .details("d")
		                                                      .touchedFiles(List.of())
		                                                      .build();
		when(diffAnalysisService.analyzeDiff(any())).thenReturn(withoutFiles);

		int exitCode = commandLine.execute("--format", "compact");

		assertEquals(0, exitCode);
		assertFalse(stdout.toString().contains("FILES:"));
	}

	private AnalyzeDiffResponse mockResponse() {
		return AnalyzeDiffResponse.builder()
		                          .title("feat:  add new feature")
		                          .summary("Added a new feature to the application")
		                          .details("Detailed description of changes")
		                          .risks(List.of(
				                          new io.github.kxng0109.rebaserescue.api.dto.RiskItem("warning", "Risk 1"),
				                          new io.github.kxng0109.rebaserescue.api.dto.RiskItem("note", "Risk 2")
		                          ))
		                          .riskScore(12)
		                          .suggestedTests(List.of("Test 1", "Test 2"))
		                          .touchedFiles(List.of("src/main/File.java"))
		                          .analysisNotes("Some notes")
		                          .metadata(AiCallMetadata.builder()
		                                                  .provider("openai")
		                                                  .modelName("gpt-4")
		                                                  .modelLatencyMs(1000)
		                                                  .tokensUsed(500)
		                                                  .build())
		                          .requestId("test-request-id")
		                          .build();
	}
}
