package io.github.kxng0109.aiprcopilot.config;

/**
 * Single source of truth for the application identity in code.
 *
 * <p>Mirrors {@code pom.xml} {@code project.version} — enforced by
 * {@code AppVersionTest}. Release automation bumps both together.
 */
public final class AppInfo {

	public static final String NAME = "ai-pr-copilot";

	public static final String VERSION = "1.2.0";

	private AppInfo() {
	}
}
