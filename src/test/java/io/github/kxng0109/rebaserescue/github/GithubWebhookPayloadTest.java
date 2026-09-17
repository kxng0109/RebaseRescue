package io.github.kxng0109.rebaserescue.github;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GithubWebhookPayloadTest {

    @Test
    void parse_shouldExtractFields() {
        String json = """
                {
                  "action": "opened",
                  "number": 42,
                  "pull_request": {
                    "number": 42,
                    "title": "Fix bug",
                    "head": {"sha": "abc123", "ref": "feature"},
                    "base": {"ref": "main", "sha": "base123"}
                  },
                  "repository": {
                    "name": "rebase-rescue",
                    "full_name": "kxng0109/RebaseRescue",
                    "owner": {"login": "kxng0109"}
                  },
                  "installation": {"id": 12345}
                }
                """;

        GithubWebhookPayload payload = GithubWebhookPayload.parse(json);

        assertThat(payload.action()).isEqualTo("opened");
        assertThat(payload.pullRequest().headSha()).isEqualTo("abc123");
        assertThat(payload.pullRequest().baseRef()).isEqualTo("main");
        assertThat(payload.repository().ownerLogin()).isEqualTo("kxng0109");
        assertThat(payload.installationId()).isEqualTo("12345");
    }

    @Test
    void parse_shouldIgnoreUnknownFields() {
        String json = """
                {"action":"synchronize","unknown_field":123,"pull_request":{"number":1,"head":{"sha":"s"},"base":{"ref":"main"}},"repository":{"name":"r","full_name":"o/r","owner":{"login":"o"}}}
                """;

        GithubWebhookPayload payload = GithubWebhookPayload.parse(json);

        assertThat(payload.action()).isEqualTo("synchronize");
    }

    @Test
    void parse_shouldFallBackToFullNameWhenOwnerMissing() {
        String json = """
                {"action":"opened","pull_request":{"number":1,"head":{"sha":"s"},"base":{"ref":"main"}},"repository":{"name":"repo","full_name":"myorg/repo"}}
                """;

        GithubWebhookPayload payload = GithubWebhookPayload.parse(json);

        assertThat(payload.repository().ownerLogin()).isEqualTo("myorg");
    }

    @Test
    void repository_ownerLogin_shouldHandleFullNameWithoutSlash() {
        GithubWebhookPayload.Repository repo = new GithubWebhookPayload.Repository("r", "noslash", null);

        assertThat(repo.ownerLogin()).isNull();
    }

    @Test
    void pullRequest_shouldHandleNullHeadAndBaseAndNumber() {
        GithubWebhookPayload.PullRequest pr = new GithubWebhookPayload.PullRequest(null, "t", null, null);

        assertThat(pr.headSha()).isNull();
        assertThat(pr.baseRef()).isNull();
        assertThat(pr.pullNumber()).isZero();
    }

    @Test
    void installationId_shouldHandleNull() {
        GithubWebhookPayload payload = new GithubWebhookPayload("opened", 1, null, null, null, null);

        assertThat(payload.installationId()).isNull();
        GithubWebhookPayload payload2 = new GithubWebhookPayload("opened", 1, null, null, new GithubWebhookPayload.Installation(null), null);
        assertThat(payload2.installationId()).isNull();
    }

    @Test
    void sender_shouldBeDeserializable() {
        String json = """
                {"action":"opened","pull_request":{"number":1,"head":{"sha":"s"},"base":{"ref":"main"}},"repository":{"name":"r","full_name":"o/r","owner":{"login":"o"}},"sender":{"login":"alice"}}
                """;

        GithubWebhookPayload payload = GithubWebhookPayload.parse(json);

        assertThat(payload.sender().login()).isEqualTo("alice");
    }

    @Test
    void parse_shouldThrowOnBlank() {
        assertThatThrownBy(() -> GithubWebhookPayload.parse("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GithubWebhookPayload.parse("{bad json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void parse_shouldThrowOnNull() {
        assertThatThrownBy(() -> GithubWebhookPayload.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void repository_ownerLogin_shouldFallBackWhenOwnerLoginNull() {
        GithubWebhookPayload.Repository repo = new GithubWebhookPayload.Repository(
                "repo", "fallback-org/repo", new GithubWebhookPayload.Owner(null));

        assertThat(repo.ownerLogin()).isEqualTo("fallback-org");
    }

    @Test
    void repository_ownerLogin_shouldReturnNullWhenNoData() {
        GithubWebhookPayload.Repository repo = new GithubWebhookPayload.Repository("r", null, null);

        assertThat(repo.ownerLogin()).isNull();
    }
}
