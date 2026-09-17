package io.github.kxng0109.rebaserescue.github;

import io.github.kxng0109.rebaserescue.api.dto.AnalyzeDiffResponse;
import io.github.kxng0109.rebaserescue.api.dto.RiskItem;
import io.github.kxng0109.rebaserescue.config.GithubProperties;
import io.github.kxng0109.rebaserescue.config.PrCopilotSarifProperties;
import io.github.kxng0109.rebaserescue.error.DiffTooLargeException;
import io.github.kxng0109.rebaserescue.service.SarifService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

/**
 * GitHub REST client for diff fetch, review post, and SARIF upload.
 *
 * <p>All calls set {@code X-GitHub-Api-Version} and use the installation
 * token. A single thread-safe {@code RestClient} is built once per bean;
 * per-request authorization headers ride each call. On 401 the cached token
 * is evicted and the call retried exactly once; any other failure, or a
 * second 401, propagates. Mutative calls are spaced ≥1s apart at the call
 * site where sequencing matters. Blocking calls are expected to run on
 * virtual threads (the webhook executor).</p>
 */
@Service
@Slf4j
public class GithubApiClient {

    private final GithubProperties properties;
    private final GithubAppAuthService authService;
    private final SarifService sarifService;
    private final PrCopilotSarifProperties sarifProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public GithubApiClient(GithubProperties properties, GithubAppAuthService authService,
            SarifService sarifService, PrCopilotSarifProperties sarifProperties,
            ObjectMapper objectMapper, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.authService = authService;
        this.sarifService = sarifService;
        this.sarifProperties = sarifProperties;
        this.objectMapper = objectMapper;
        String baseUrl = GithubApiHostPolicy.normalize(
                properties.getApi().getBaseUrl(), properties.getApi().getAllowedHosts());
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", properties.getApi().getApiVersion())
                .build();
    }

    private Long resolveInstallationId(Long installationId) {
        Long id = installationId != null ? installationId : properties.getApp().getInstallationId();
        if (id == null) {
            throw new IllegalStateException("github.app.installation-id not configured");
        }
        return id;
    }

    /**
     * Runs a token-authenticated call, evicting the cached token and retrying
     * exactly once on 401. Any other failure, or a second 401, propagates.
     *
     * @param installationId the installation, or {@code null} for the configured default
     * @param call the call receiving the bearer token
     * @return the call result
     */
    private <T> T withTokenRefresh(Long installationId, Function<String, T> call) {
        Long id = resolveInstallationId(installationId);
        try {
            return call.apply("Bearer " + authService.getInstallationToken(id));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() != HttpStatus.UNAUTHORIZED.value()) {
                throw e;
            }
            log.debug("GitHub API returned 401; evicting token and retrying once");
            authService.evictToken(id);
            return call.apply("Bearer " + authService.getInstallationToken(id));
        }
    }

    /**
     * Fetches the raw unified diff for a pull request.
     *
     * @param owner the repository owner, must not be blank
     * @param repo the repository name, must not be blank
     * @param pullNumber the PR number
     * @return raw diff text, never {@code null}
     */
    public String fetchDiff(String owner, String repo, int pullNumber) {
        return fetchDiff(owner, repo, pullNumber, properties.getApp().getInstallationId());
    }

    String fetchDiff(String owner, String repo, int pullNumber, Long installationId) {
        return withTokenRefresh(installationId, token -> {
            byte[] body = restClient.get()
                    .uri("/repos/{owner}/{repo}/pulls/{number}", owner, repo, pullNumber)
                    .header(HttpHeaders.AUTHORIZATION, token)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github.diff")
                    .retrieve()
                    .body(byte[].class);
            if (body == null) {
                return "";
            }
            return new String(body, StandardCharsets.UTF_8);
        });
    }

    /**
     * Posts a review with inline comments derived from analysis risks.
     *
     * @param owner the repository owner
     * @param repo the repository name
     * @param pullNumber the PR number
     * @param commitSha the head SHA to pin the review to
     * @param analysis the analysis response
     * @param installationId the installation to authenticate as, or {@code null} for default
     */
    public void postReview(String owner, String repo, int pullNumber, String commitSha,
                           AnalyzeDiffResponse analysis, Long installationId) {
        withTokenRefresh(installationId, token -> {
            Map<String, Object> body = buildReviewBody(commitSha, analysis);
            restClient.post()
                    .uri("/repos/{owner}/{repo}/pulls/{number}/reviews", owner, repo, pullNumber)
                    .header(HttpHeaders.AUTHORIZATION, token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("GitHub review posted for {}/{}#{} commit {}", owner, repo, pullNumber, commitSha);
            return null;
        });
    }

    /**
     * Uploads SARIF to code scanning.
     *
     * @param owner the repository owner
     * @param repo the repository name
     * @param commitSha the 40-hex commit SHA
     * @param ref the full git ref, e.g. {@code refs/pull/123/head}
     * @param analysis the analysis response
     * @param installationId the installation to authenticate as, or {@code null} for default
     */
    public void uploadSarif(String owner, String repo, String commitSha, String ref,
                            AnalyzeDiffResponse analysis, Long installationId) {
        withTokenRefresh(installationId, token -> {
            Map<String, Object> sarifDoc = sarifService.toSarif(analysis);
        // Ensure automationDetails.id carries the configured category for GitHub's
        // post-June-2025 multi-run rejection (distinct tool+category per upload).
        String category = properties.getSarif().getCategory();
        if (category != null && !category.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> runs = (List<Map<String, Object>>) sarifDoc.get("runs");
                if (runs != null && !runs.isEmpty()) {
                    Map<String, Object> firstRun = runs.get(0);
                    Object existing = firstRun.get("automationDetails");
                    Map<String, Object> automation = new LinkedHashMap<>();
                    if (existing instanceof Map<?, ?> m) {
                        for (Map.Entry<?, ?> e : m.entrySet()) {
                            automation.put(String.valueOf(e.getKey()), e.getValue());
                        }
                    }
                    automation.put("id", category);
                    firstRun.put("automationDetails", automation);
                }
            } catch (Exception e) {
                log.warn("Could not set SARIF automationDetails.id to '{}': {}", category, e.getMessage());
            }
        }
        String sarifJson;
        try {
            sarifJson = objectMapper.writeValueAsString(sarifDoc);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize SARIF", e);
        }
        long sarifBytes = sarifJson.getBytes(StandardCharsets.UTF_8).length;
        if (sarifBytes > sarifProperties.getMaxBytes()) {
            throw new DiffTooLargeException(
                    "SARIF document of " + sarifBytes + " bytes exceeds the "
                            + sarifProperties.getMaxBytes() + " byte budget");
        }
        String encoded = gzipBase64(sarifJson);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commit_sha", commitSha);
        payload.put("ref", ref);
        payload.put("sarif", encoded);
        // tool_name defaults to API; keep SARIF driver name as filter key
        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
                .uri("/repos/{owner}/{repo}/code-scanning/sarifs", owner, repo)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(Map.class);
        Object sarifId = response != null ? response.get("id") : null;
        log.info("GitHub SARIF upload accepted for {}/{} commit {} id={}", owner, repo, commitSha, sarifId);
        if (sarifId != null) {
            pollSarif(owner, repo, sarifId.toString(), token);
        }
        return null;
        });
    }

    private void pollSarif(String owner, String repo, String sarifId, String token) {
        for (int i = 0; i < 10; i++) {
            try {
                Thread.sleep(i == 0 ? 2000 : 3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> status = restClient.get()
                        .uri("/repos/{owner}/{repo}/code-scanning/sarifs/{id}", owner, repo, sarifId)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .retrieve()
                        .body(Map.class);
                if (status == null) {
                    return;
                }
                Object state = status.get("processing_status");
                if ("complete".equals(state)) {
                    log.info("GitHub SARIF processing complete for {}/{} id={}", owner, repo, sarifId);
                    return;
                }
                if ("failed".equals(state)) {
                    log.warn("GitHub SARIF processing failed for {}/{} id={}: {}", owner, repo, sarifId, status.get("errors"));
                    return;
                }
            } catch (Exception e) {
                log.warn("GitHub SARIF poll attempt failed for {}/{} id={}: {}",
                        owner, repo, sarifId, e.getMessage());
            }
        }
        log.warn("GitHub SARIF poll timed out for {}/{} id={}", owner, repo, sarifId);
    }

    private Map<String, Object> buildReviewBody(String commitSha, AnalyzeDiffResponse analysis) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (commitSha != null && !commitSha.isBlank()) {
            body.put("commit_id", commitSha.trim());
        }
        String summary = buildSummary(analysis);
        body.put("body", summary);
        boolean hasError = analysis.risks() != null && analysis.risks().stream()
                .anyMatch(r -> "error".equalsIgnoreCase(r.level()));
        body.put("event", hasError ? "REQUEST_CHANGES" : "COMMENT");
        List<RiskItem> risks = analysis.risks() == null ? List.of() : analysis.risks();
        List<String> touched = analysis.touchedFiles() == null ? List.of() : analysis.touchedFiles();
        String targetPath = touched.isEmpty() ? null : touched.get(0);
        List<Map<String, Object>> comments = new ArrayList<>();
        for (RiskItem risk : risks) {
            if (risk.message() == null || risk.message().isBlank()) {
                continue;
            }
            // Only inline on error/warning for signal; notes stay in summary.
            if (!"error".equalsIgnoreCase(risk.level()) && !"warning".equalsIgnoreCase(risk.level())) {
                continue;
            }
            if (targetPath == null) {
                continue;
            }
            Map<String, Object> comment = new LinkedHashMap<>();
            comment.put("path", targetPath);
            comment.put("line", 1);
            comment.put("side", "RIGHT");
            String commentBody = "**" + risk.level().toUpperCase() + "**: " + risk.message();
            comment.put("body", commentBody);
            comments.add(comment);
            if (comments.size() >= 10) {
                break;
            }
        }
        if (!comments.isEmpty()) {
            body.put("comments", comments);
        }
        return body;
    }

    private String buildSummary(AnalyzeDiffResponse analysis) {
        StringBuilder sb = new StringBuilder();
        sb.append("## RebaseRescue analysis\n\n");
        if (analysis.title() != null) {
            sb.append("**").append(analysis.title()).append("**\n\n");
        }
        if (analysis.summary() != null) {
            sb.append(analysis.summary()).append("\n\n");
        }
        if (analysis.details() != null) {
            sb.append(analysis.details()).append("\n\n");
        }
        List<RiskItem> risks = analysis.risks() == null ? List.of() : analysis.risks();
        if (!risks.isEmpty()) {
            sb.append("### Risks (").append(risks.size()).append(")\n");
            for (RiskItem risk : risks) {
                sb.append("- **").append(risk.level()).append("**: ").append(risk.message()).append("\n");
            }
            sb.append("\n");
        }
        if (analysis.suggestedTests() != null && !analysis.suggestedTests().isEmpty()) {
            sb.append("### Suggested tests\n");
            for (String t : analysis.suggestedTests()) {
                sb.append("- ").append(t).append("\n");
            }
        }
        return sb.toString();
    }

    static String gzipBase64(String json) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
                gzip.write(json.getBytes(StandardCharsets.UTF_8));
            }
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to gzip SARIF", e);
        }
    }
}
