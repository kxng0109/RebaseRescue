package io.github.kxng0109.aiprcopilot.github;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GithubApiHostPolicyTest {

    @Test
    void normalize_shouldReturnDefaultWhenBlank() {
        assertThat(GithubApiHostPolicy.normalize(null)).isEqualTo("https://api.github.com");
        assertThat(GithubApiHostPolicy.normalize("  ")).isEqualTo("https://api.github.com");
    }

    @Test
    void normalize_shouldCanonicalizeDefaultVariants() {
        assertThat(GithubApiHostPolicy.normalize("https://api.github.com/"))
                .isEqualTo("https://api.github.com");
        assertThat(GithubApiHostPolicy.normalize("HTTPS://API.GITHUB.COM"))
                .isEqualTo("https://api.github.com");
    }

    @Test
    void validate_shouldAcceptDefault() {
        GithubApiHostPolicy.validate("https://api.github.com");
        GithubApiHostPolicy.validate("https://api.github.com/");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://api.github.com",
            "https://api.github.com:8080",
            "https://api.github.com/api/v3",
            "https://api.github.com?x=1",
            "https://user:pass@api.github.com",
            "https://127.0.0.1",
            "https://10.0.0.1",
            "https://192.168.1.1",
            "https://169.254.169.254",
            "https://localhost",
            "https://metadata.google.internal",
            "https://evil.example.com",
            "not-a-url",
            ""
    })
    void validate_shouldRejectNonAllowlistedTargets(String candidate) {
        assertThatThrownBy(() -> GithubApiHostPolicy.validate(candidate))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalize_shouldRejectBadHost() {
        assertThatThrownBy(() -> GithubApiHostPolicy.normalize("http://evil.example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_shouldAcceptListedEnterpriseHost() {
        List<String> allowed = List.of("api.github.com", "github.example.com");

        GithubApiHostPolicy.validate("https://github.example.com", allowed);
        assertThat(GithubApiHostPolicy.normalize("https://github.example.com/", allowed))
                .isEqualTo("https://github.example.com");
    }

    @Test
    void validate_shouldRejectUnlistedEnterpriseHost() {
        List<String> allowed = List.of("api.github.com");

        assertThatThrownBy(
                () -> GithubApiHostPolicy.validate("https://github.example.com", allowed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowlisted");
    }

    @Test
    void validate_shouldAcceptListedPrivateAddressButRejectMetadata() {
        List<String> allowed = List.of("api.github.com", "10.20.30.40");

        GithubApiHostPolicy.validate("https://10.20.30.40", allowed);

        List<String> metadataListed = List.of("api.github.com", "169.254.169.254");
        assertThatThrownBy(
                () -> GithubApiHostPolicy.validate("https://169.254.169.254", metadataListed))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                () -> GithubApiHostPolicy.validate("https://api.github.com", metadataListed))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_shouldRejectEmptyOrBlankAllowlist() {
        assertThatThrownBy(() -> GithubApiHostPolicy.validate("https://api.github.com", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                () -> GithubApiHostPolicy.validate("https://api.github.com", List.of("  ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GithubApiHostPolicy.validate("https://api.github.com", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
