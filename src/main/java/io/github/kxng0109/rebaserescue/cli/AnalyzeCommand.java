package io.github.kxng0109.rebaserescue.cli;

import io.github.kxng0109.rebaserescue.api.dto.AnalyzeDiffRequest;
import io.github.kxng0109.rebaserescue.api.dto.AnalyzeDiffResponse;
import io.github.kxng0109.rebaserescue.config.AppInfo;
import io.github.kxng0109.rebaserescue.service.DiffAnalysisService;
import io.github.kxng0109.rebaserescue.service.GitService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

@Component
@RequiredArgsConstructor
@CommandLine.Command(
		name = "analyze",
		description = "Analyze git changes for PR review using AI.",
		mixinStandardHelpOptions = true,
		versionProvider = AnalyzeCommand.AnalyzeVersionProvider.class
)
public class AnalyzeCommand implements Callable<Integer> {

	private final GitService gitService;
	private final DiffAnalysisService diffAnalysisService;
	private final ObjectMapper objectMapper;
	@CommandLine.Option(
			names = {"-b", "--base"},
			description = "Base branch to compare against"
	)
	private String baseBranch;
	@CommandLine.Option(
			names = {"-s", "--staged"},
			description = "Analyze only staged changes"
	)
	private boolean stagedOnly = false;
	@CommandLine.Option(
			names = {"-u", "--uncommitted"},
			description = "Analyze all uncommitted changes (staged and unstaged)"
	)
	private boolean uncommitted = false;
	@CommandLine.Option(
			names = {"-l", "--language"},
			description = "Programming language for analysis output formatting (e.g., 'en' for English, 'es' for Spanish)",
			defaultValue = "en"
	)
	private String language;
	@CommandLine.Option(
			names = {"--style"},
			description = "Style of the analysis output (e.g., 'conventional-commits')",
			defaultValue = "conventional-commits"
	)
	private String style;
	@CommandLine.Option(
			names = {"--max-summary-length"},
			description = "Maximum length of the summary output in characters"
	)
	private Integer maxSummaryLength;
	@CommandLine.Option(
			names = {"--format"},
			description = "Output format: json or compact",
			defaultValue = "json"
	)
	private String format = "json";
	@CommandLine.Option(
			names = {"-q", "--quiet"},
			description = "Suppress progress messages (result output only)"
	)
	private boolean quiet = false;
	@CommandLine.Spec
	private CommandLine.Model.CommandSpec spec;

	@Override
	public Integer call() {
		try {
			if (!gitService.isGitRepository()) {
				spec.commandLine().getErr().println(
						"Error: Not a git repository. Please run this command from within a git repository.");
				return 1;
			}

			String diff = getDiff();

			if (diff == null || diff.isBlank()) {
				spec.commandLine().getOut().println("No changes detected.");
				return 0;
			}

			progress("Analyzing changes...");
			AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
			                                               .diff(diff)
			                                               .language(language)
			                                               .style(style)
			                                               .maxSummaryLength(maxSummaryLength)
			                                               .requestId(UUID.randomUUID().toString())
			                                               .build();

			AnalyzeDiffResponse response = diffAnalysisService.analyzeDiff(request);

			printResult(response);

			return 0;
		} catch (Exception e) {
			spec.commandLine().getErr().println("Error during analysis: " + e.getMessage());
			return 1;
		}
	}

	private void progress(String message) {
		if (!quiet) {
			spec.commandLine().getOut().println(message);
		}
	}

	private void printResult(AnalyzeDiffResponse response) {
		var out = spec.commandLine().getOut();
		if ("compact".equalsIgnoreCase(format)) {
			out.println("TITLE: " + response.title());
			out.println("SUMMARY:");
			out.println(response.summary());
			out.println("DETAILS:");
			out.println(response.details());
			if (response.touchedFiles() != null && !response.touchedFiles().isEmpty()) {
				out.println("FILES:");
				response.touchedFiles().forEach(f -> out.println("- " + f));
			}
		} else {
			out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(response));
		}
		out.flush();
	}

	private String getDiff() {
		if (baseBranch != null && !baseBranch.isBlank()) {
			//If the user specifies a base branch, use that
			if (!gitService.branchExists(baseBranch)) {
				throw new RuntimeException(String.format(
						"Error: Base branch '%s' does not exist.", baseBranch
				));
			}

			progress("Comparing against base branch: " + baseBranch);
			return gitService.getDiffAgainstBranch(baseBranch);
		} else if (uncommitted) {
			//If the user wants to analyze all uncommitted changes
			progress("Analyzing all uncommitted changes (staged and unstaged)");
			return gitService.getUncommittedDiff();
		} else if (stagedOnly || gitService.hasStagedChanges()) {
			//If the user wants to analyze staged changes only, or if there are staged changes
			progress("Analyzing staged changes only");
			return gitService.getStagedDiff();
		} else {
			//No staged changes, try to detect base branch
			progress("No staged changes. Detecting base branch.");
			for (String branch : List.of("main", "master", "develop")) {
				if (gitService.branchExists(branch)) {
					try {
						//If the branch exists, try to get the diff against it
						String diff = gitService.getDiffAgainstBranch(branch);
						if (diff != null && !diff.isBlank()) {
							progress("Comparing against branch: " + branch);
							return diff;
						}
					} catch (RuntimeException _) {
						//Ignore and try the next branch
					}
				}
			}
		}
		return null;
	}

	/**
	 * No-arg version provider so picocli can instantiate it via any factory (the command itself requires constructor
	 * injection and has no default ctor).
	 */
	public static class AnalyzeVersionProvider implements CommandLine.IVersionProvider {
		@Override
		public String[] getVersion() {
			return new String[]{AppInfo.NAME + " " + AppInfo.VERSION};
		}
	}
}
