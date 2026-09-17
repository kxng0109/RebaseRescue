package io.github.kxng0109.rebaserescue.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class GitService {

	private static final int DEFAULT_TIMEOUT_SECONDS = 30;

	private final int timeoutSeconds;

	private final String workingDirectory;

	public GitService(int timeoutSeconds, String workingDirectory) {
		this.timeoutSeconds = timeoutSeconds;
		this.workingDirectory = workingDirectory;
	}

	public GitService(String workingDirectory) {
		this(DEFAULT_TIMEOUT_SECONDS, workingDirectory);
	}

	public GitService(int timeoutSeconds) {
		this(timeoutSeconds, detectWorkingDirectory());
	}

	public GitService() {
		this(DEFAULT_TIMEOUT_SECONDS, detectWorkingDirectory());
	}


	private static String detectWorkingDirectory() {
		return resolveWorkingDirectory(System.getenv("PWD"));
	}

	/**
	 * Resolves the working directory from a candidate path, extracted for testability ({@code System.getenv} cannot be
	 * stubbed in-JVM).
	 *
	 * @param pwd the {@code PWD} candidate; may be {@code null}
	 * @return the candidate when it is a live directory, otherwise the process directory
	 */
	static String resolveWorkingDirectory(String pwd) {
		//For MacOS and Linux
		if (pwd != null && !pwd.isEmpty()) {
			File pwdFile = new File(pwd);
			if (pwdFile.exists() && pwdFile.isDirectory()) {
				log.debug("Detected working directory from PWD: {}", pwd);
				return pwd;
			}
		}

		// Fallback: get absolute path of current directory
		String fallback = new File(".").getAbsoluteFile().getParent();
		log.debug("Using fallback working directory: {}", fallback);
		return fallback;
	}

	public String getStagedDiff() {
		log.debug("Retrieving staged changes...");
		String result = runCommand("git", "--no-pager", "diff", "--staged");
		log.debug("Retrieved {} characters of staged diff", result.length());
		return result;
	}

	public boolean hasStagedChanges() {
		log.debug("Checking if staged changes...");
		try {
			// --quiet --exit-code: exit 1 when staged changes exist, no output buffered.
			runCommand("git", "--no-pager", "diff", "--staged", "--quiet", "--exit-code");
			return false;
		} catch (GitExitCodeException e) {
			return e.exitCode() == 1;
		} catch (RuntimeException e) {
			log.debug("Failed to check for staged changes", e);
			return false;
		}
	}

	public boolean isGitRepository() {
		try {
			runCommand("git", "rev-parse", "--git-dir");
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}

	public String getCurrentBranchName() {
		log.debug("Getting current branch...");
		try {
			return runCommand("git", "symbolic-ref", "--short", "HEAD").trim();
		} catch (RuntimeException e) {
			return runCommand("git", "rev-parse", "--abbrev-ref", "HEAD").trim();
		}
	}

	public boolean branchExists(String branchName) {
		try {
			runCommand("git", "rev-parse", "--verify", branchName);
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}

	public String getDiffAgainstBranch(String baseBranch) {
		log.debug("Retrieving diff against branch: {}", baseBranch);
		// Using three-dot diff to show changes introduced by current branch
		String result = runCommand("git", "--no-pager", "diff", baseBranch + "...HEAD");
		log.debug("Retrieved {} characters of branch diff", result.length());
		return result;
	}

	public String getUncommittedDiff() {
		log.debug("Retrieving uncommitted changes...");
		String result = runCommand("git", "--no-pager", "diff", "HEAD");
		log.debug("Retrieved {} characters of uncommitted diff", result.length());
		return result;
	}

	private String runCommand(String... command) {
		ProcessBuilder processBuilder = new ProcessBuilder(command);

		File workingDir = new File(workingDirectory);
		log.debug("Using working directory: {}", workingDir.getAbsolutePath());

		processBuilder.directory(workingDir);

		// Disable Git pager to prevent interactive prompts
		processBuilder.environment().put("GIT_PAGER", "cat");

		Process process = null;
		try {
			process = processBuilder.start();

			StringBuilder outputBuilder = new StringBuilder();
			StringBuilder errorBuilder = new StringBuilder();

			Process finalProcess = process;
			Thread outputThread = new Thread(() -> {
				try {
					outputBuilder.append(new String(
							finalProcess.getInputStream().readAllBytes(),
							StandardCharsets.UTF_8
					));
				} catch (IOException e) {
					log.debug("Error reading output stream", e);
				}
			});

			Thread errorThread = new Thread(() -> {
				try {
					errorBuilder.append(new String(
							finalProcess.getErrorStream().readAllBytes(),
							StandardCharsets.UTF_8
					));
				} catch (IOException e) {
					log.debug("Error reading error stream", e);
				}
			});

			outputThread.start();
			errorThread.start();

			// Just to prevent the process from hanging indefinitely
			boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

			if (!finished) {
				process.destroyForcibly();
				throw new RuntimeException(
						String.format("Timeout while waiting for process('%s') to finish", String.join(" ", command)));
			}

			outputThread.join(1000);
			errorThread.join(1000);

			String output = outputBuilder.toString().trim();
			String error = errorBuilder.toString().trim();
			int exitCode = process.exitValue();

			if (exitCode != 0) {
				throw new GitExitCodeException(
						String.format(
								"Command ('%s') failed with exit code: %d\n %s",
								String.join(" ", command),
								exitCode,
								error
						),
						exitCode
				);
			}

			return output;
		} catch (IOException e) {
			throw new RuntimeException(
					String.format(
							"Failed to execute command: %s. %s \n %s ",
							String.join(" ", command),
							e.getMessage(),
							e
					)
			);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(String.format(
					"Command ('%s') interrupted. %s",
					String.join(" ", command),
					e
			));
		} finally {
			if (process != null && process.isAlive()) {
				process.destroyForcibly();
			}
		}
	}

	/**
	 * Non-zero git exit with the code preserved, so callers can distinguish meaningful codes (e.g. {@code diff --quiet}
	 * exit 1 = differences exist).
	 */
	public static final class GitExitCodeException extends RuntimeException {
		private final int exitCode;

		GitExitCodeException(String message, int exitCode) {
			super(message);
			this.exitCode = exitCode;
		}

		public int exitCode() {
			return exitCode;
		}
	}
}
