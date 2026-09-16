package io.github.kxng0109.aiprcopilot.github;

import io.github.kxng0109.aiprcopilot.api.dto.AnalyzeDiffResponse;
import io.github.kxng0109.aiprcopilot.config.GithubProperties;
import io.github.kxng0109.aiprcopilot.service.DiffAnalysisService;
import io.github.kxng0109.aiprcopilot.api.dto.AnalyzeDiffRequest;
import java.util.concurrent.Executor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Background handler for GitHub {@code pull_request} webhooks.
 *
 * <p>Offloads the minutes-long AI analysis onto virtual threads (via the
 * async executor) so the webhook controller can return 202 within the 10s
 * GitHub deadline. Handles action filtering, diff fetching, analysis, and
 * dual delivery (review + SARIF).</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GithubWebhookService {

    private final GithubProperties properties;
    private final GithubApiClient githubApiClient;
    private final DiffAnalysisService diffAnalysisService;
    private final Executor githubWebhookExecutor;

    /**
     * Processes a verified webhook delivery asynchronously.
     *
     * @param deliveryId the {@code X-GitHub-Delivery} GUID for correlation, must not be {@code null}
     * @param event the {@code X-GitHub-Event} value, must not be {@code null}
     * @param payload the raw webhook JSON, must not be {@code null}
     */
    public void handleAsync(String deliveryId, String event, String payload) {
        githubWebhookExecutor.execute(() -> {
            try {
                handleSync(deliveryId, event, payload);
            } catch (Exception e) {
                log.error("GitHub webhook async handling failed for delivery {} event {}: {}",
                        deliveryId, event, e.getMessage(), e);
            }
        });
    }

    private void handleSync(String deliveryId, String event, String payload) {
        GithubWebhookPayload parsed;
        try {
            parsed = GithubWebhookPayload.parse(payload);
        } catch (Exception e) {
            log.warn("GitHub webhook delivery {}: could not parse payload: {}", deliveryId, e.getMessage());
            return;
        }
        if (!"pull_request".equals(event)) {
            log.debug("GitHub webhook delivery {}: ignoring event {}", deliveryId, event);
            return;
        }
        String action = parsed.action();
        if (!isRelevantAction(action)) {
            log.debug("GitHub webhook delivery {}: ignoring action {}", deliveryId, action);
            return;
        }
        if (parsed.pullRequest() == null || parsed.repository() == null) {
            log.warn("GitHub webhook delivery {}: missing pull_request or repository", deliveryId);
            return;
        }
        String owner = parsed.repository().ownerLogin();
        String repo = parsed.repository().name();
        int prNumber = parsed.pullRequest().pullNumber();
        String headSha = parsed.pullRequest().headSha();
        String baseRef = parsed.pullRequest().baseRef();

        log.info("GitHub webhook delivery {}: pull_request {} {}/{}#{} head={}",
                deliveryId, action, owner, repo, prNumber, headSha);

        String diff;
        try {
            diff = githubApiClient.fetchDiff(owner, repo, prNumber);
        } catch (Exception e) {
            log.error("GitHub webhook delivery {}: failed to fetch diff for {}/{}#{}: {}",
                    deliveryId, owner, repo, prNumber, e.getMessage(), e);
            return;
        }
        if (diff == null || diff.isBlank()) {
            log.info("GitHub webhook delivery {}: diff empty for {}/{}#{}", deliveryId, owner, repo, prNumber);
            return;
        }

        AnalyzeDiffRequest request = AnalyzeDiffRequest.builder()
                .diff(diff)
                .requestId(deliveryId)
                .build();

        AnalyzeDiffResponse analysis;
        try {
            analysis = diffAnalysisService.analyzeDiff(request);
        } catch (Exception e) {
            log.error("GitHub webhook delivery {}: analysis failed for {}/{}#{}: {}",
                    deliveryId, owner, repo, prNumber, e.getMessage(), e);
            return;
        }

        String installationId = parsed.installationId();
        Long installId = null;
        if (installationId != null) {
            try {
                installId = Long.valueOf(installationId);
            } catch (NumberFormatException ignored) {
                installId = properties.getApp().getInstallationId();
            }
        } else {
            installId = properties.getApp().getInstallationId();
        }

        try {
            githubApiClient.postReview(owner, repo, prNumber, headSha, analysis, installId);
        } catch (Exception e) {
            log.error("GitHub webhook delivery {}: failed to post review for {}/{}#{}: {}",
                    deliveryId, owner, repo, prNumber, e.getMessage(), e);
        }

        try {
            String ref = "refs/pull/" + prNumber + "/head";
            if (baseRef != null && !baseRef.isBlank()) {
                // Use pull head ref as required by code-scanning API.
                ref = "refs/pull/" + prNumber + "/head";
            }
            githubApiClient.uploadSarif(owner, repo, headSha != null ? headSha : "HEAD", ref, analysis, installId);
        } catch (Exception e) {
            log.error("GitHub webhook delivery {}: failed to upload SARIF for {}/{}#{}: {}",
                    deliveryId, owner, repo, prNumber, e.getMessage(), e);
        }
    }

    private static boolean isRelevantAction(String action) {
        return "opened".equals(action)
                || "synchronize".equals(action)
                || "ready_for_review".equals(action)
                || "reopened".equals(action);
    }
}
