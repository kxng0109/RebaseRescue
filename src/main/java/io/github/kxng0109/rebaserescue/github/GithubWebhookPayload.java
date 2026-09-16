package io.github.kxng0109.rebaserescue.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal pull_request webhook payload model. Unknown fields are ignored
 * so GitHub's frequent additive changes do not break deserialization.
 * Only the fields required for analysis are mapped.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubWebhookPayload(
        String action,
        @JsonProperty("number") Integer number,
        @JsonProperty("pull_request") PullRequest pullRequest,
        Repository repository,
        @JsonProperty("installation") Installation installation,
        Sender sender
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequest(
            Integer number,
            String title,
            Head head,
            Base base
    ) {
        public String headSha() {
            return head != null ? head.sha() : null;
        }

        public String baseRef() {
            return base != null ? base.ref() : null;
        }

        public int pullNumber() {
            return number != null ? number : 0;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Head(String sha, String ref) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Base(String ref, String sha) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(String name, @JsonProperty("full_name") String fullName, Owner owner) {
        public String ownerLogin() {
            if (owner != null && owner.login() != null) {
                return owner.login();
            }
            if (fullName != null && fullName.contains("/")) {
                return fullName.substring(0, fullName.indexOf('/'));
            }
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Owner(String login) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Installation(Long id) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sender(String login) {}

    public String installationId() {
        return installation != null && installation.id() != null ? installation.id().toString() : null;
    }

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /**
     * Parses raw webhook JSON.
     *
     * @param json the raw body string, must not be {@code null}
     * @return parsed payload, never {@code null}
     * @throws IllegalArgumentException when parsing fails
     */
    public static GithubWebhookPayload parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("payload must not be blank");
        }
        try {
            return MAPPER.readValue(json, GithubWebhookPayload.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse GitHub webhook payload", e);
        }
    }
}
