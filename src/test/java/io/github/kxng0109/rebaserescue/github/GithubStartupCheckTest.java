package io.github.kxng0109.rebaserescue.github;

import io.github.kxng0109.rebaserescue.config.GithubProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GithubStartupCheckTest {

    private static GithubProperties enabledProps(String secret, String baseUrl) {
        GithubProperties props = new GithubProperties();
        props.setEnabled(true);
        props.getWebhook().setSecret(secret);
        props.getApi().setBaseUrl(baseUrl);
        return props;
    }

    @Test
    void validateConfiguration_shouldPassWhenDisabledRegardlessOfValues() {
        GithubProperties props = new GithubProperties();
        props.setEnabled(false);
        props.getWebhook().setSecret("  ");
        props.getApi().setBaseUrl("http://evil.example.com");

        assertThatCode(() -> new GithubStartupCheck(props).validateConfiguration())
                .doesNotThrowAnyException();
    }

    @Test
    void validateConfiguration_shouldPassWhenEnabledAndValid() {
        GithubProperties props = enabledProps("s3cret", "https://api.github.com");

        assertThatCode(() -> new GithubStartupCheck(props).validateConfiguration())
                .doesNotThrowAnyException();
    }

    @Test
    void validateConfiguration_shouldFailWhenSecretBlank() {
        GithubProperties props = enabledProps("  ", "https://api.github.com");

        assertThatThrownBy(() -> new GithubStartupCheck(props).validateConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("webhook.secret");
    }

    @Test
    void validateConfiguration_shouldFailWhenSecretNull() {
        GithubProperties props = enabledProps(null, "https://api.github.com");

        assertThatThrownBy(() -> new GithubStartupCheck(props).validateConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("webhook.secret");
    }

    @Test
    void validateConfiguration_shouldUseDefaultForBlankBaseUrl() {
        GithubProperties props = enabledProps("s3cret", "  ");

        assertThatCode(() -> new GithubStartupCheck(props).validateConfiguration())
                .doesNotThrowAnyException();

        GithubProperties nullBase = enabledProps("s3cret", "https://api.github.com");
        nullBase.getApi().setBaseUrl(null);

        assertThatCode(() -> new GithubStartupCheck(nullBase).validateConfiguration())
                .doesNotThrowAnyException();
    }

    @Test
    void validateConfiguration_shouldWarnButPassForPrivateAllowlistEntry() {
        GithubProperties props = enabledProps("s3cret", "https://10.20.30.40");
        props.getApi().setAllowedHosts(List.of("api.github.com", "10.20.30.40"));

        assertThatCode(() -> new GithubStartupCheck(props).validateConfiguration())
                .doesNotThrowAnyException();
    }

    @Test
    void validateConfiguration_shouldFailWhenBaseUrlNotAllowlisted() {
        GithubProperties props = enabledProps("s3cret", "http://evil.example.com");

        assertThatThrownBy(() -> new GithubStartupCheck(props).validateConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("github.api");
    }

    @Test
    void validateConfiguration_shouldPassForListedEnterpriseHost() {
        GithubProperties props = enabledProps("s3cret", "https://github.example.com");
        props.getApi().setAllowedHosts(List.of("api.github.com", "github.example.com"));

        assertThatCode(() -> new GithubStartupCheck(props).validateConfiguration())
                .doesNotThrowAnyException();
    }

    @Test
    void validateConfiguration_shouldFailForUnlistedEnterpriseHost() {
        GithubProperties props = enabledProps("s3cret", "https://github.example.com");

        assertThatThrownBy(() -> new GithubStartupCheck(props).validateConfiguration())
                .isInstanceOf(IllegalStateException.class);
    }
}
