package io.github.kxng0109.rebaserescue.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enforces the single-source versioning rule: {@link AppInfo#VERSION} must match {@code pom.xml}
 * {@code project/version}. Bump both together on release.
 */
class AppVersionTest {

	@Test
	void appInfoVersion_shouldMatchPomVersion() throws Exception {
		Path pom = Path.of("pom.xml");
		String content = Files.readString(pom);
		Matcher matcher = Pattern.compile(
				"<artifactId>rebase-rescue</artifactId>\\s*<version>([^<]+)</version>").matcher(content);

		assertThat(matcher.find()).isTrue();
		assertThat(AppInfo.VERSION).isEqualTo(matcher.group(1).trim());
		assertThat(AppInfo.NAME).isEqualTo("rebase-rescue");
	}

	@Test
	void appInfo_shouldNotBeInstantiable() throws Exception {
		var constructor = AppInfo.class.getDeclaredConstructor();
		constructor.setAccessible(true);

		assertThat(constructor.newInstance()).isInstanceOf(AppInfo.class);
	}
}
