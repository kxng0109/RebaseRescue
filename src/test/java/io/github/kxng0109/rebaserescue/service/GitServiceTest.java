package io.github.kxng0109.rebaserescue.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class GitServiceTest {
	@TempDir
	Path tempDir;

	private GitService gitService;

	@BeforeEach
	void setUp() throws Exception {
		initGitRepo(tempDir);
		gitService = new GitService(30, tempDir.toString());
	}

	@Test
	void isGitRepository_inGitRepo_shouldReturnTrue() {
		assertTrue(gitService.isGitRepository());
	}

	@Test
	void isGitRepository_outsideGitRepo_shouldReturnFalse(@TempDir Path nonGitDir) {
		GitService nonGitService = new GitService(30, nonGitDir.toString());
		assertFalse(nonGitService.isGitRepository());
	}

	@Test
	void hasStagedChanges_withNoChanges_shouldReturnFalse() {
		assertFalse(gitService.hasStagedChanges());
	}

	@Test
	void hasStagedChanges_withStagedFiles_shouldReturnTrue() throws Exception {
		createAndStageFile("test.txt", "hello world");
		assertTrue(gitService.hasStagedChanges());
	}

	@Test
	void hasStagedChanges_withUnstagedChanges_shouldReturnFalse() throws Exception {
		Files.writeString(tempDir.resolve("unstaged.txt"), "content");
		assertFalse(gitService.hasStagedChanges());
	}

	@Test
	void getStagedDiff_withStagedChanges_shouldReturnDiff() throws Exception {
		createAndStageFile("test.txt", "hello world");

		String diff = gitService.getStagedDiff();

		assertNotNull(diff);
		assertFalse(diff.isEmpty());
		assertTrue(diff.contains("test.txt") || diff.contains("hello world"));
	}

	@Test
	void getStagedDiff_withNoChanges_shouldReturnEmpty() {
		String diff = gitService.getStagedDiff();
		assertTrue(diff.isEmpty());
	}

	@Test
	void getUncommittedDiff_withStagedAndUnstagedChanges_shouldReturnAll() throws Exception {
		createAndStageFile("initial.txt", "initial");
		runGitCommand("commit", "-m", "initial commit");

		createAndStageFile("staged. txt", "staged content");

		Files.writeString(tempDir.resolve("initial.txt"), "modified");

		String diff = gitService.getUncommittedDiff();

		assertNotNull(diff);
		assertFalse(diff.isEmpty());
	}

	@Test
	void getCurrentBranchName_shouldReturnBranchName() {
		String branch = gitService.getCurrentBranchName();

		assertNotNull(branch);
		assertTrue(branch.equals("main") || branch.equals("master"));
	}

	@Test
	void branchExists_withExistingBranch_shouldReturnTrue() throws Exception {
		createAndStageFile("test.txt", "content");
		runGitCommand("commit", "-m", "initial");

		String currentBranch = gitService.getCurrentBranchName();
		assertTrue(gitService.branchExists(currentBranch));
	}

	@Test
	void branchExists_withNonExistingBranch_shouldReturnFalse() {
		assertFalse(gitService.branchExists("non-existing-branch-12345"));
	}

	@Test
	void getDiffAgainstBranch_shouldReturnDiffBetweenBranches() throws Exception {
		createAndStageFile("initial.txt", "initial");
		runGitCommand("commit", "-m", "initial commit");

		String mainBranch = gitService.getCurrentBranchName();

		runGitCommand("checkout", "-b", "feature");

		createAndStageFile("feature.txt", "feature content");
		runGitCommand("commit", "-m", "feature commit");

		String diff = gitService.getDiffAgainstBranch(mainBranch);

		assertNotNull(diff);
		assertTrue(diff.contains("feature.txt"));
	}

	private void initGitRepo(Path dir) throws Exception {
		runCommand(dir, "git", "init");
		runCommand(dir, "git", "config", "user.email", "test@example. com");
		runCommand(dir, "git", "config", "user.name", "Test User");
	}

	private void createAndStageFile(String filename, String content) throws Exception {
		Files.writeString(tempDir.resolve(filename), content);
		runGitCommand("add", filename);
	}

	private void runGitCommand(String... args) throws Exception {
		String[] fullCommand = new String[args.length + 1];
		fullCommand[0] = "git";
		System.arraycopy(args, 0, fullCommand, 1, args.length);
		runCommand(tempDir, fullCommand);
	}

	private void runCommand(Path dir, String... command) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(command)
				.directory(dir.toFile())
				.redirectErrorStream(true);

		Process process = pb.start();
		int exitCode = process.waitFor();

		if (exitCode != 0) {
			String output = new String(process.getInputStream().readAllBytes());
			throw new RuntimeException("Command failed: " + String.join(" ", command) + "\n" + output);
		}

		Thread.sleep(50);
	}

	@Test
	void getDiffAgainstBranch_withNonExistentBranch_shouldThrow() {
		assertThrows(
				GitService.GitExitCodeException.class,
				() -> gitService.getDiffAgainstBranch("non-existing-branch-12345")
		);
	}

	@Test
	void getCurrentBranchName_withDetachedHead_shouldFallBack() throws Exception {
		createAndStageFile("initial.txt", "initial");
		runGitCommand("commit", "-m", "initial commit");
		runGitCommand("checkout", "--detach");

		assertEquals("HEAD", gitService.getCurrentBranchName());
	}

	@Test
	void isGitRepository_withMissingWorkingDirectory_shouldReturnFalse() {
		GitService broken = new GitService(30, "no-such-dir-xyz-123");

		assertFalse(broken.isGitRepository());
	}

	@Test
	void constructors_shouldDetectWorkingDirectory() {
		GitService detected = new GitService(30);

		assertNotNull(detected);
	}

	@Test
	void constructor_shouldAcceptDirectoryOnly() {
		GitService single = new GitService(tempDir.toString());

		assertNotNull(single);
	}

	@Test
	void resolveWorkingDirectory_shouldPreferValidPwd() {
		assertEquals(tempDir.toString(), GitService.resolveWorkingDirectory(tempDir.toString()));
	}

	@Test
	void resolveWorkingDirectory_shouldFallBack_whenPwdMissingOrInvalid(@TempDir Path other) throws Exception {
		String fallback = GitService.resolveWorkingDirectory(null);
		assertNotNull(fallback);
		assertEquals(fallback, GitService.resolveWorkingDirectory(""));
		assertEquals(fallback, GitService.resolveWorkingDirectory("no-such-dir-xyz-123"));

		Path plainFile = other.resolve("file.txt");
		Files.writeString(plainFile, "x");
		assertEquals(fallback, GitService.resolveWorkingDirectory(plainFile.toString()));
	}

	@Test
	void hasStagedChanges_withNonGitDirectory_shouldReturnFalse(@TempDir Path nonGitDir) {
		GitService nonGitService = new GitService(30, nonGitDir.toString());

		assertFalse(nonGitService.hasStagedChanges());
	}

	@Test
	void hasStagedChanges_withTimeout_shouldReturnFalse() {
		GitService impatient = new GitService(0, tempDir.toString());

		assertFalse(impatient.hasStagedChanges());
	}

	@Test
	void runCommand_shouldThrow_whenTimeoutExpires() {
		GitService impatient = new GitService(0, tempDir.toString());

		RuntimeException exception = assertThrows(
				RuntimeException.class,
				impatient::getStagedDiff
		);
		assertTrue(exception.getMessage().contains("Timeout while waiting"));
	}
}
