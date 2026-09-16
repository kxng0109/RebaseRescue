package io.github.kxng0109.rebaserescue.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CliRunnerTest {

	@Mock
	private AnalyzeCommand analyzeCommand;

	@Mock
	private CommandLine.IFactory factory;

	@Test
	void run_shouldExecuteAnalyzeAndStoreExitCode() throws Exception {
		CliRunner runner = new CliRunner(analyzeCommand, factory);

		try (
				MockedConstruction<CommandLine> commands = Mockito.mockConstruction(
						CommandLine.class,
						(mock, context) -> when(mock.execute(any())).thenReturn(3)
				)
		) {
			runner.run("analyze", "--staged");

			assertThat(commands.constructed()).hasSize(1);
			verify(commands.constructed().getFirst()).execute("--staged");
			assertThat(runner.getExitCode()).isEqualTo(3);
		}
	}

	@Test
	void run_shouldStoreZero_whenCommandSucceeds() throws Exception {
		CliRunner runner = new CliRunner(analyzeCommand, factory);

		try (
				MockedConstruction<CommandLine> commands = Mockito.mockConstruction(
						CommandLine.class,
						(mock, context) -> when(mock.execute(any())).thenReturn(0)
				)
		) {
			runner.run("analyze");

			assertThat(commands.constructed()).hasSize(1);
			verify(commands.constructed().getFirst()).execute();
			assertThat(runner.getExitCode()).isZero();
		}
	}

	@Test
	void getExitCode_shouldDefaultToZero() {
		assertThat(new CliRunner(analyzeCommand, factory).getExitCode()).isZero();
	}

	@Test
	void run_shouldDoNothing_whenNoArgs() throws Exception {
		CliRunner runner = new CliRunner(analyzeCommand, factory);

		try (
				MockedConstruction<CommandLine> commands =
						Mockito.mockConstruction(CommandLine.class)
		) {
			runner.run();

			assertThat(commands.constructed()).isEmpty();
			assertThat(runner.getExitCode()).isZero();
		}
	}

	@Test
	void run_shouldDoNothing_whenFirstArgIsNotAnalyze() throws Exception {
		CliRunner runner = new CliRunner(analyzeCommand, factory);

		try (
				MockedConstruction<CommandLine> commands =
						Mockito.mockConstruction(CommandLine.class)
		) {
			runner.run("server", "--port=8080");

			assertThat(commands.constructed()).isEmpty();
			assertThat(runner.getExitCode()).isZero();
		}
	}
}
