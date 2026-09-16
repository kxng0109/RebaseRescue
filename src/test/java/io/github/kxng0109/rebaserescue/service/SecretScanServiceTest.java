package io.github.kxng0109.rebaserescue.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class SecretScanServiceTest {

	private final SecretScanService scanService = new SecretScanService();

	@Test
	void scan_shouldBlockAwsKey() {
		SecretScanService.ScanResult result =
				scanService.scan("diff --git\n+key = AKIAIOSFODNN7EXAMPLE\n");

		assertThat(result.verdict()).isEqualTo(SecretScanService.Verdict.BLOCKED);
		assertThat(result.reason()).contains("aws-access-key");
		assertThat(result.text()).isEmpty();
	}

	@Test
	void scan_shouldBlockGithubToken() {
		String token = "ghp_" + "a".repeat(36);
		SecretScanService.ScanResult result = scanService.scan("+token=" + token);

		assertThat(result.verdict()).isEqualTo(SecretScanService.Verdict.BLOCKED);
	}

	@Test
	void scan_shouldBlockPrivateKeyBlock() {
		SecretScanService.ScanResult result =
				scanService.scan("+-----BEGIN RSA PRIVATE KEY-----\n+MIIB");

		assertThat(result.verdict()).isEqualTo(SecretScanService.Verdict.BLOCKED);
	}

	@Test
	void scan_shouldRedactGenericHighEntropyAssignment() {
		String secret = "x".repeat(10) + "Q7f9K2mZvX4pL8sN6qW3eR5tY1uI0oP";
		SecretScanService.ScanResult result =
				scanService.scan("diff\n+api_key = \"" + secret + "\"\n");

		assertThat(result.verdict()).isEqualTo(SecretScanService.Verdict.REDACTED);
		assertThat(result.text()).contains("[REDACTED-SECRET]");
		assertThat(result.text()).doesNotContain(secret);
	}

	@Test
	void scan_shouldPassCleanDiff() {
		SecretScanService.ScanResult result =
				scanService.scan("diff --git a/Foo.java b/Foo.java\n+public void ok() {}");

		assertThat(result.verdict()).isEqualTo(SecretScanService.Verdict.CLEAN);
		assertThat(result.text()).contains("public void ok()");
	}

	@Test
	void scan_shouldPassLowEntropyAssignment() {
		SecretScanService.ScanResult result =
				scanService.scan("+password = \"changeme\"\n");

		assertThat(result.verdict()).isEqualTo(SecretScanService.Verdict.CLEAN);
	}

	@Test
	void scan_shouldHandleNullAndEmpty() {
		assertThat(scanService.scan(null).verdict()).isEqualTo(SecretScanService.Verdict.CLEAN);
		assertThat(scanService.scan("").verdict()).isEqualTo(SecretScanService.Verdict.CLEAN);
	}

	@Test
	void shannonEntropy_shouldScoreUniformLowAndRandomHigh() {
		assertThat(SecretScanService.shannonEntropy("aaaaaaaaaaaaaaaa")).isLessThan(1.0);
		assertThat(SecretScanService.shannonEntropy("Q7f9K2mZvX4pL8sN6qW3eR5tY1uI0oP")).isGreaterThan(4.0);
	}

	@Test
	void describe_shouldNameMatchedRulesWithoutValues() {
		String text = "AKIAIOSFODNN7EXAMPLE and ghp_" + "b".repeat(36);

		assertThat(scanService.describe(text)).contains("aws-access-key", "github-token");
		assertThat(scanService.describe(text).toString()).doesNotContain("AKIAIOSFODNN7EXAMPLE");
	}

	@Test
	void scan_shouldBlockBase64ObfuscatedToken() {
		String encoded = Base64.getEncoder().encodeToString(
				("ghp_" + "a".repeat(36)).getBytes(StandardCharsets.UTF_8));

		assertThat(scanService.scan("blob " + encoded + " end").verdict())
				.isEqualTo(SecretScanService.Verdict.BLOCKED);
	}

	@Test
	void scan_shouldBlockRot13ObfuscatedToken() {
		assertThat(scanService.scan("blob " + rot13("ghp_" + "a".repeat(36)) + " end").verdict())
				.isEqualTo(SecretScanService.Verdict.BLOCKED);
	}

	@Test
	void scan_shouldBlockZeroWidthObfuscatedToken() {
		String disguised = "ghp_" + "a".repeat(18) + Character.toString(0x200B) + "a".repeat(18);

		assertThat(scanService.scan("blob " + disguised + " end").verdict())
				.isEqualTo(SecretScanService.Verdict.BLOCKED);
	}

	@Test
	void scan_shouldBlockKnownProviderKeys() {
		assertThat(scanService.scan("key = \"sk-proj-abcdefghijklmnopqrst\"").verdict())
				.isEqualTo(SecretScanService.Verdict.BLOCKED);
		assertThat(scanService.scan("key = \"sk-ant-abcdefghij\"").verdict())
				.isEqualTo(SecretScanService.Verdict.BLOCKED);
		assertThat(scanService.scan("token = \"xoxb-1234567890\"").verdict())
				.isEqualTo(SecretScanService.Verdict.BLOCKED);
		assertThat(scanService.scan("token = \"glpat-abcdefghijklmnopqrst\"").verdict())
				.isEqualTo(SecretScanService.Verdict.BLOCKED);
	}

	@Test
	void scan_shouldStayClean_whenBase64CandidateDoesNotDecode() {
		assertThat(scanService.scan("xx aaaaaaaaaaaaaaaaa yy").verdict())
				.isEqualTo(SecretScanService.Verdict.CLEAN);
	}

	@Test
	void scan_shouldWarnOnly_whenPiiShaped() {
		assertThat(scanService.scan("contact a@b.com for details").verdict())
				.isEqualTo(SecretScanService.Verdict.CLEAN);
	}

	@Test
	void describe_shouldReturnEmpty_whenClean() {
		assertThat(scanService.describe("diff --git a/F.java")).isEmpty();
		assertThat(scanService.describe(null)).isEmpty();
	}

	private static String rot13(String value) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c >= 'a' && c <= 'z') {
				c = (char) ((c - 'a' + 13) % 26 + 'a');
			} else if (c >= 'A' && c <= 'Z') {
				c = (char) ((c - 'A' + 13) % 26 + 'A');
			}
			out.append(c);
		}
		return out.toString();
	}
}
