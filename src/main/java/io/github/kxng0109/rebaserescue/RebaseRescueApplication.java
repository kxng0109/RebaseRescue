package io.github.kxng0109.rebaserescue;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;
import java.util.List;

@SpringBootApplication
public class RebaseRescueApplication {

	private static final List<String> SKIPPED_ARGS = List.of("--help", "-h", "--version", "-V");

	static void main(String[] args) {
		if (args.length > 0 && "analyze".equals(args[0])) {
			runCliMode(args);
		} else {
			SpringApplication.run(RebaseRescueApplication.class, args);
		}
	}

	private static void runCliMode(String[] args) {
		// Start Spring context without the web server; CliRunner executes the command.
		SpringApplication app = new SpringApplication(RebaseRescueApplication.class);
		app.setWebApplicationType(WebApplicationType.NONE);

		// Disable banner and reduce logging for the CLI
		app.setBannerMode(Banner.Mode.OFF);
		System.setProperty("logging.level.root", "WARN");

		// AppStartupCheck throws when the default provider is not configured, but that
		// must not block --help/--version output.
		boolean shouldSkipCheck = Arrays.stream(args).anyMatch(SKIPPED_ARGS::contains);
		if (shouldSkipCheck) {
			System.setProperty("app.skip-startup-check", "true");
		}

		// CliRunner (CommandLineRunner + ExitCodeGenerator) runs the command and
		// supplies the process status; exit() closes the context gracefully first.
		// Server mode intentionally keeps plain run(): it is long-lived and must
		// not exit after startup.
		System.exit(SpringApplication.exit(app.run(args)));
	}
}
