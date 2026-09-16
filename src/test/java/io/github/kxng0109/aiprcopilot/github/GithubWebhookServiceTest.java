package io.github.kxng0109.aiprcopilot.github;

import io.github.kxng0109.aiprcopilot.api.dto.AnalyzeDiffResponse;
import io.github.kxng0109.aiprcopilot.config.GithubProperties;
import io.github.kxng0109.aiprcopilot.service.DiffAnalysisService;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GithubWebhookServiceTest {

    @Mock
    private GithubApiClient apiClient;

    @Mock
    private DiffAnalysisService diffAnalysisService;

    private GithubProperties props() {
        GithubProperties p = new GithubProperties();
        p.setEnabled(true);
        p.getApp().setInstallationId(42L);
        return p;
    }

    private GithubWebhookService service(Executor executor) {
        return new GithubWebhookService(props(), apiClient, diffAnalysisService, executor);
    }

    private static String payload(String action, int number, String headSha) {
        return """
                {"action":"%s","number":%d,"pull_request":{"number":%d,"title":"t","head":{"sha":"%s","ref":"feat"},"base":{"ref":"main","sha":"base"}},"repository":{"name":"r","full_name":"o/r","owner":{"login":"o"}},"installation":{"id":42}}
                """.formatted(action, number, number, headSha);
    }

    @Test
    void handleAsync_shouldIgnoreNonPullRequestEvent() {
        GithubWebhookService svc = service(Runnable::run);
        svc.handleAsync("d1", "push", payload("opened", 1, "abc"));
        verify(apiClient, never()).fetchDiff(anyString(), anyString(), anyInt());
    }

    @Test
    void handleAsync_shouldIgnoreIrrelevantAction() {
        GithubWebhookService svc = service(Runnable::run);
        svc.handleAsync("d1", "pull_request", payload("closed", 1, "abc"));
        verify(apiClient, never()).fetchDiff(anyString(), anyString(), anyInt());
    }

    @Test
    void handleAsync_shouldProcessOpenedSynchronouslyWithDirectExecutor() {
        lenient().when(apiClient.fetchDiff(anyString(), anyString(), anyInt())).thenReturn("diff --git a/F.java");
        AnalyzeDiffResponse response = AnalyzeDiffResponse.builder().title("t").build();
        when(diffAnalysisService.analyzeDiff(any())).thenReturn(response);
        GithubWebhookService svc = service(Runnable::run);

        svc.handleAsync("d1", "pull_request", payload("opened", 7, "sha123"));

        verify(apiClient).fetchDiff(eq("o"), eq("r"), eq(7));
        verify(diffAnalysisService).analyzeDiff(any());
        verify(apiClient).postReview(eq("o"), eq("r"), eq(7), eq("sha123"), any(), eq(42L));
        verify(apiClient).uploadSarif(eq("o"), eq("r"), eq("sha123"), eq("refs/pull/7/head"), any(), eq(42L));
    }

    @Test
    void handleAsync_shouldReturnEarlyWhenDiffEmpty() {
        when(apiClient.fetchDiff(anyString(), anyString(), anyInt())).thenReturn("  ");
        GithubWebhookService svc = service(Runnable::run);

        svc.handleAsync("d1", "pull_request", payload("opened", 1, "abc"));

        verify(diffAnalysisService, never()).analyzeDiff(any());
        verify(apiClient, never()).postReview(anyString(), anyString(), anyInt(), anyString(), any(), any());
    }

    @Test
    void handleAsync_shouldHandleFetchDiffFailureGracefully() {
        when(apiClient.fetchDiff(anyString(), anyString(), anyInt())).thenThrow(new RuntimeException("network down"));
        GithubWebhookService svc = service(Runnable::run);

        svc.handleAsync("d1", "pull_request", payload("opened", 1, "abc"));

        verify(diffAnalysisService, never()).analyzeDiff(any());
    }

    @Test
    void handleAsync_shouldHandleAnalysisFailureGracefully() {
        when(apiClient.fetchDiff(anyString(), anyString(), anyInt())).thenReturn("diff");
        when(diffAnalysisService.analyzeDiff(any())).thenThrow(new RuntimeException("ai down"));
        GithubWebhookService svc = service(Runnable::run);

        svc.handleAsync("d1", "pull_request", payload("opened", 1, "abc"));

        verify(apiClient, never()).postReview(anyString(), anyString(), anyInt(), anyString(), any(), any());
    }

    @Test
    void handleAsync_shouldHandleInvalidPayloadGracefully() {
        GithubWebhookService svc = service(Runnable::run);
        svc.handleAsync("d1", "pull_request", "not json at all");
        verify(apiClient, never()).fetchDiff(anyString(), anyString(), anyInt());
    }

    @Test
    void handleAsync_shouldHandleMissingPullRequest() {
        String json = """
                {"action":"opened","repository":{"name":"r","full_name":"o/r","owner":{"login":"o"}},"installation":{"id":42}}
                """;
        GithubWebhookService svc = service(Runnable::run);
        svc.handleAsync("d1", "pull_request", json);
        verify(apiClient, never()).fetchDiff(anyString(), anyString(), anyInt());
    }

    @Test
    void handleAsync_shouldHandleMissingRepository() {
        String json = """
                {"action":"opened","number":9,"pull_request":{"number":9,"title":"t","head":{"sha":"sha9","ref":"feat"},"base":{"ref":"main","sha":"base"}}}
                """;
        GithubWebhookService svc = service(Runnable::run);
        svc.handleAsync("d9", "pull_request", json);
        verify(apiClient, never()).fetchDiff(anyString(), anyString(), anyInt());
    }

    @Test
    void handleAsync_shouldSkipAnalysisWhenDiffNull() {
        when(apiClient.fetchDiff(anyString(), anyString(), anyInt())).thenReturn(null);
        GithubWebhookService svc = service(Runnable::run);

        svc.handleAsync("d1", "pull_request", payload("opened", 1, "abc"));

        verify(diffAnalysisService, never()).analyzeDiff(any());
    }

    @Test
    void handleAsync_shouldUseHeadFallbackAndPullRefVariants() {
        lenient().when(apiClient.fetchDiff(anyString(), anyString(), anyInt())).thenReturn("diff");
        AnalyzeDiffResponse response = AnalyzeDiffResponse.builder().title("t").build();
        when(diffAnalysisService.analyzeDiff(any())).thenReturn(response);
        GithubWebhookService svc = service(Runnable::run);
        String nullBase = """
                {"action":"opened","number":11,"pull_request":{"number":11,"title":"t","head":{"sha":null,"ref":"feat"},"base":{"ref":null}},"repository":{"name":"r","full_name":"o/r","owner":{"login":"o"}},"installation":{"id":42}}
                """;
        String blankBase = """
                {"action":"opened","number":12,"pull_request":{"number":12,"title":"t","head":{"sha":"sha12","ref":"feat"},"base":{"ref":"  "}},"repository":{"name":"r","full_name":"o/r","owner":{"login":"o"}},"installation":{"id":42}}
                """;

        svc.handleAsync("d11", "pull_request", nullBase);
        svc.handleAsync("d12", "pull_request", blankBase);

        verify(apiClient).uploadSarif(eq("o"), eq("r"), eq("HEAD"), eq("refs/pull/11/head"), any(), eq(42L));
        verify(apiClient).uploadSarif(eq("o"), eq("r"), eq("sha12"), eq("refs/pull/12/head"), any(), eq(42L));
    }

    @Test
    void handleAsync_shouldProcessSynchronizeAndReadyForReview() {
        lenient().when(apiClient.fetchDiff(anyString(), anyString(), anyInt())).thenReturn("diff");
        AnalyzeDiffResponse response = AnalyzeDiffResponse.builder().title("t").build();
        when(diffAnalysisService.analyzeDiff(any())).thenReturn(response);
        GithubWebhookService svc = service(Runnable::run);

        svc.handleAsync("d1", "pull_request", payload("synchronize", 2, "sha2"));
        svc.handleAsync("d2", "pull_request", payload("ready_for_review", 3, "sha3"));
        svc.handleAsync("d3", "pull_request", payload("reopened", 4, "sha4"));

        verify(apiClient).fetchDiff(eq("o"), eq("r"), eq(2));
        verify(apiClient).fetchDiff(eq("o"), eq("r"), eq(3));
        verify(apiClient).fetchDiff(eq("o"), eq("r"), eq(4));
    }

    @Test
    void handleAsync_shouldHandlePostReviewAndSarifFailuresGracefully() {
        lenient().when(apiClient.fetchDiff(anyString(), anyString(), anyInt())).thenReturn("diff");
        AnalyzeDiffResponse response = AnalyzeDiffResponse.builder().title("t").build();
        when(diffAnalysisService.analyzeDiff(any())).thenReturn(response);
        org.mockito.Mockito.doThrow(new RuntimeException("review down"))
                .when(apiClient).postReview(anyString(), anyString(), anyInt(), anyString(), any(), any());
        org.mockito.Mockito.doThrow(new RuntimeException("sarif down"))
                .when(apiClient).uploadSarif(anyString(), anyString(), anyString(), anyString(), any(), any());
        GithubWebhookService svc = service(Runnable::run);

        svc.handleAsync("d1", "pull_request", payload("opened", 5, "sha5"));

        verify(apiClient).postReview(anyString(), anyString(), anyInt(), anyString(), any(), any());
        verify(apiClient).uploadSarif(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void handleAsync_shouldUseDefaultInstallationIdWhenPayloadHasNoInstallation() {
        lenient().when(apiClient.fetchDiff(anyString(), anyString(), anyInt())).thenReturn("diff");
        AnalyzeDiffResponse response = AnalyzeDiffResponse.builder().title("t").build();
        when(diffAnalysisService.analyzeDiff(any())).thenReturn(response);
        GithubWebhookService svc = service(Runnable::run);
        String json = """
                {"action":"opened","number":6,"pull_request":{"number":6,"title":"t","head":{"sha":"sha6","ref":"feat"},"base":{"ref":"main","sha":"base"}},"repository":{"name":"r","full_name":"o/r","owner":{"login":"o"}}}
                """;

        svc.handleAsync("d6", "pull_request", json);

        verify(apiClient).fetchDiff(eq("o"), eq("r"), eq(6));
        verify(apiClient).postReview(eq("o"), eq("r"), eq(6), eq("sha6"), any(), eq(42L));
    }
}
