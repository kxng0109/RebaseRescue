package io.github.kxng0109.rebaserescue.github;

import io.github.kxng0109.rebaserescue.api.dto.AnalyzeDiffResponse;
import io.github.kxng0109.rebaserescue.api.dto.RiskItem;
import io.github.kxng0109.rebaserescue.config.GithubProperties;
import io.github.kxng0109.rebaserescue.config.PrCopilotSarifProperties;
import io.github.kxng0109.rebaserescue.error.DiffTooLargeException;
import io.github.kxng0109.rebaserescue.service.SarifService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GithubApiClientTest {

    @Mock
    private GithubAppAuthService authService;

    @Mock
    private SarifService sarifService;

    @Mock
    private PrCopilotSarifProperties sarifProperties;

    @Mock
    private RestClient.Builder restClientBuilder;

    @Mock
    private RestClient restClient;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private GithubProperties props() {
        GithubProperties p = new GithubProperties();
        p.setEnabled(true);
        p.getApp().setAppId("123");
        p.getApp().setPrivateKey("dummy");
        p.getApp().setInstallationId(42L);
        p.getApi().setBaseUrl("https://api.github.com/");
        p.getApi().setApiVersion("2026-03-10");
        p.getSarif().setCategory("rebase-rescue");
        lenient().when(sarifProperties.getMaxBytes()).thenReturn(5000000L);
        return p;
    }

    private void stubBuilder() {
        lenient().when(restClientBuilder.baseUrl(anyString())).thenReturn(restClientBuilder);
        lenient().when(restClientBuilder.defaultHeader(anyString(), anyString())).thenReturn(restClientBuilder);
        lenient().when(restClientBuilder.build()).thenReturn(restClient);
    }

    @SuppressWarnings("unchecked")
    private void stubFetchDiff(String diff) {
        RestClient.RequestHeadersUriSpec getSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.get()).thenReturn(getSpec);
        when(getSpec.uri(anyString(), any(Object.class), any(Object.class), any(Object.class))).thenReturn(headersSpec);
        when(headersSpec.header(anyString(), anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(any(Class.class))).thenReturn(diff.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void gzipBase64_shouldRoundTrip() throws Exception {
        String json = "{\"version\":\"2.1.0\",\"runs\":[]}";
        String encoded = GithubApiClient.gzipBase64(json);
        byte[] gzipped = Base64.getDecoder().decode(encoded);
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(gzipped))) {
            String decoded = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(decoded).isEqualTo(json);
        }
    }

    @Test
    void fetchDiff_shouldReturnDiffString() {
        GithubProperties p = props();
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        stubFetchDiff("diff --git a/F.java");
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        String diff = client.fetchDiff("o", "r", 1, 42L);

        assertThat(diff).isEqualTo("diff --git a/F.java");
    }

    @Test
    void fetchDiff_shouldHandleBaseUrlWithoutTrailingSlash() {
        GithubProperties p = props();
        p.getApi().setBaseUrl("https://api.github.com");
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        stubFetchDiff("diff --git a/F.java");
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        assertThat(client.fetchDiff("o", "r", 1, 42L)).isNotBlank();
    }

    @Test
    void fetchDiff_shouldFallbackToDefaultBaseUrlWhenBlank() {
        GithubProperties p = props();
        p.getApi().setBaseUrl("  ");
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        stubFetchDiff("diff --git a/F.java");
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        assertThat(client.fetchDiff("o", "r", 1, 42L)).isNotBlank();
    }

    @Test
    void fetchDiff_shouldReturnEmptyWhenBodyNull() {
        GithubProperties p = props();
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        RestClient.RequestHeadersUriSpec getSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.get()).thenReturn(getSpec);
        when(getSpec.uri(anyString(), any(Object.class), any(Object.class), any(Object.class))).thenReturn(headersSpec);
        when(headersSpec.header(anyString(), anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(any(Class.class))).thenReturn(null);
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        assertThat(client.fetchDiff("o", "r", 1, 42L)).isEmpty();
    }

    @Test
    void fetchDiff_shouldThrowWhenInstallationMissing() {
        GithubProperties p = props();
        p.getApp().setInstallationId(null);
        stubBuilder();
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        assertThatThrownBy(() -> client.fetchDiff("o", "r", 1, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    void postReview_shouldIncludeInlineCommentsForErrorAndWarning() {
        GithubProperties p = props();
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString(), any(Object.class), any(Object.class), any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.body(any(Map.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(null);
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        AnalyzeDiffResponse analysis = AnalyzeDiffResponse.builder()
                .title("t")
                .summary("s")
                .details("d")
                .risks(List.of(new RiskItem("error", "boom"), new RiskItem("note", "typo")))
                .touchedFiles(List.of("src/Main.java"))
                .build();

        client.postReview("o", "r", 1, "abc123", analysis, 42L);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(bodySpec).body(captor.capture());
        Map<String, Object> body = captor.getValue();
        assertThat(body.get("event")).isEqualTo("REQUEST_CHANGES");
        assertThat(body.get("commit_id")).isEqualTo("abc123");
        List<Map<String, Object>> comments = (List<Map<String, Object>>) body.get("comments");
        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).get("path")).isEqualTo("src/Main.java");
    }

    @SuppressWarnings("unchecked")
    @Test
    void postReview_shouldUseCommentWhenNoError() {
        GithubProperties p = props();
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString(), any(Object.class), any(Object.class), any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.body(any(Map.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(null);
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        AnalyzeDiffResponse analysis = AnalyzeDiffResponse.builder()
                .title("t")
                .summary("s")
                .risks(List.of(new RiskItem("note", "typo")))
                .touchedFiles(List.of("src/Main.java"))
                .build();

        client.postReview("o", "r", 1, "abc", analysis, 42L);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(bodySpec).body(captor.capture());
        assertThat(captor.getValue().get("event")).isEqualTo("COMMENT");
        assertThat(captor.getValue().containsKey("comments")).isFalse();
    }

    @SuppressWarnings("unchecked")
    @Test
    void uploadSarif_shouldPostAndPollToComplete() {
        GithubProperties p = props();
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        Map<String, Object> sarifDoc = Map.of("version", "2.1.0", "runs", List.of(Map.of("tool", Map.of("driver", Map.of("name", "rebase-rescue")))));
        when(sarifService.toSarif(any())).thenReturn(new java.util.LinkedHashMap<>(sarifDoc));

        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec postResponse = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString(), any(Object.class), any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.body(any(Map.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(postResponse);
        when(postResponse.body(any(Class.class))).thenReturn(Map.of("id", "sarif-123"));

        RestClient.RequestHeadersUriSpec getSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec getResponse = mock(RestClient.ResponseSpec.class);
        when(restClient.get()).thenReturn(getSpec);
        when(getSpec.uri(anyString(), any(Object.class), any(Object.class), any(Object.class))).thenReturn(headersSpec);
        when(headersSpec.header(anyString(), anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(getResponse);
        when(getResponse.body(any(Class.class))).thenReturn(Map.of("processing_status", "complete"));

        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        AnalyzeDiffResponse analysis = AnalyzeDiffResponse.builder().title("t").build();

        client.uploadSarif("o", "r", "abc123def456abc123def456abc123def456abcd", "refs/pull/1/head", analysis, 42L);

        verify(bodySpec).body(any(Map.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void postReview_shouldHandleNullCommitAndEmptyLists() {
        GithubProperties p = props();
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString(), any(Object.class), any(Object.class), any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.body(any(Map.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(null);
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        AnalyzeDiffResponse analysis = AnalyzeDiffResponse.builder()
                .title("t")
                .risks(null)
                .touchedFiles(null)
                .build();

        client.postReview("o", "r", 1, "  ", analysis, 42L);
        client.postReview("o", "r", 1, null, analysis, 42L);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(bodySpec, times(2)).body(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(
                body -> assertThat(body.containsKey("commit_id")).isFalse());
        assertThat(captor.getValue().containsKey("comments")).isFalse();
    }

    @SuppressWarnings("unchecked")
    @Test
    void uploadSarif_shouldMergeExistingAutomationDetails() {
        GithubProperties p = props();
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        Map<String, Object> firstRun = new LinkedHashMap<>();
        firstRun.put("tool", Map.of("driver", Map.of("name", "rebase-rescue")));
        Map<String, Object> existing = new LinkedHashMap<>();
        existing.put("id", "old-category");
        existing.put("custom", "keep-me");
        firstRun.put("automationDetails", existing);
        Map<String, Object> sarifDoc = new LinkedHashMap<>();
        sarifDoc.put("version", "2.1.0");
        sarifDoc.put("runs", new ArrayList<>(List.of(firstRun)));
        when(sarifService.toSarif(any())).thenReturn(sarifDoc);
        stubUploadPost(Map.of());
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        AnalyzeDiffResponse analysis = AnalyzeDiffResponse.builder().title("t").build();

        client.uploadSarif("o", "r", "abc123def456abc123def456abc123def456abcd", "refs/pull/1/head", analysis, 42L);

        assertThat(firstRun.get("automationDetails")).isInstanceOf(Map.class);
        Map<String, Object> automation = (Map<String, Object>) firstRun.get("automationDetails");
        assertThat(automation.get("id")).isEqualTo("rebase-rescue");
        assertThat(automation.get("custom")).isEqualTo("keep-me");
    }

    @SuppressWarnings("unchecked")
    @Test
    void uploadSarif_shouldSkipMergeForNonMapAutomationDetails() {
        GithubProperties p = props();
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        Map<String, Object> firstRun = new LinkedHashMap<>();
        firstRun.put("tool", Map.of("driver", Map.of("name", "rebase-rescue")));
        firstRun.put("automationDetails", "v1");
        Map<String, Object> sarifDoc = new LinkedHashMap<>();
        sarifDoc.put("version", "2.1.0");
        sarifDoc.put("runs", new ArrayList<>(List.of(firstRun)));
        when(sarifService.toSarif(any())).thenReturn(sarifDoc);
        stubUploadPost(Map.of());
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        AnalyzeDiffResponse analysis = AnalyzeDiffResponse.builder().title("t").build();

        client.uploadSarif("o", "r", "abc123def456abc123def456abc123def456abcd", "refs/pull/1/head", analysis, 42L);

        assertThat(((Map<String, Object>) firstRun.get("automationDetails")).get("id"))
                .isEqualTo("rebase-rescue");
    }

    @SuppressWarnings("unchecked")
    @Test
    void uploadSarif_shouldTolerateImmutableRunEntry() {
        GithubProperties p = props();
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        Map<String, Object> sarifDoc = new LinkedHashMap<>();
        sarifDoc.put("version", "2.1.0");
        sarifDoc.put("runs", List.of(Map.of("tool", Map.of("driver", Map.of("name", "rebase-rescue")))));
        when(sarifService.toSarif(any())).thenReturn(sarifDoc);
        stubUploadPost(Map.of());
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        AnalyzeDiffResponse analysis = AnalyzeDiffResponse.builder().title("t").build();

        client.uploadSarif("o", "r", "abc123def456abc123def456abc123def456abcd", "refs/pull/1/head", analysis, 42L);

        verify(lastUploadBodySpec).body(any(Map.class));
    }

    @Test
    void uploadSarif_shouldThrowWhenSerializationFails() {
        GithubProperties p = props();
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        Map<String, Object> sarifDoc = new LinkedHashMap<>();
        sarifDoc.put("version", "2.1.0");
        sarifDoc.put("runs", null);
        when(sarifService.toSarif(any())).thenReturn(sarifDoc);
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, failingMapper, restClientBuilder);

        AnalyzeDiffResponse analysis = AnalyzeDiffResponse.builder().title("t").build();

        assertThatThrownBy(() -> client.uploadSarif(
                "o", "r", "abc123def456abc123def456abc123def456abcd", "refs/pull/1/head", analysis, 42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("serialize");
    }

    @SuppressWarnings("unchecked")
    @Test
    void uploadSarif_shouldHandleNullCategory() {
        GithubProperties p = props();
        p.getSarif().setCategory(null);
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        Map<String, Object> sarifDoc = new LinkedHashMap<>();
        sarifDoc.put("version", "2.1.0");
        sarifDoc.put("runs", null);
        when(sarifService.toSarif(any())).thenReturn(sarifDoc);
        stubUploadPost(Map.of());
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        AnalyzeDiffResponse analysis = AnalyzeDiffResponse.builder().title("t").build();

        client.uploadSarif("o", "r", "abc123def456abc123def456abc123def456abcd", "refs/pull/1/head", analysis, 42L);

        verify(lastUploadBodySpec).body(any(Map.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void uploadSarif_shouldPollThroughPendingToComplete() {
        GithubProperties p = props();
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        Map<String, Object> sarifDoc = new LinkedHashMap<>();
        sarifDoc.put("version", "2.1.0");
        sarifDoc.put("runs", null);
        when(sarifService.toSarif(any())).thenReturn(sarifDoc);
        stubUploadPost(Map.of("id", "sarif-1"));
        stubPollGet(List.of(
                Map.of("processing_status", "pending"),
                Map.of("processing_status", "complete")));
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        AnalyzeDiffResponse analysis = AnalyzeDiffResponse.builder().title("t").build();

        client.uploadSarif("o", "r", "abc123def456abc123def456abc123def456abcd", "refs/pull/1/head", analysis, 42L);

        verify(lastPollSpec, times(2)).body(any(Class.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void uploadSarif_shouldStopOnFailedPollStatus() {
        GithubProperties p = props();
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        Map<String, Object> sarifDoc = new LinkedHashMap<>();
        sarifDoc.put("version", "2.1.0");
        sarifDoc.put("runs", null);
        when(sarifService.toSarif(any())).thenReturn(sarifDoc);
        stubUploadPost(Map.of("id", "sarif-2"));
        stubPollGet(List.of(Map.of("processing_status", "failed", "errors", List.of("bad"))));
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        AnalyzeDiffResponse analysis = AnalyzeDiffResponse.builder().title("t").build();

        client.uploadSarif("o", "r", "abc123def456abc123def456abc123def456abcd", "refs/pull/1/head", analysis, 42L);

        verify(lastPollSpec, times(1)).body(any(Class.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void uploadSarif_shouldContinueAfterTransientPollFailure() {
        GithubProperties p = props();
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        Map<String, Object> sarifDoc = new LinkedHashMap<>();
        sarifDoc.put("version", "2.1.0");
        sarifDoc.put("runs", null);
        when(sarifService.toSarif(any())).thenReturn(sarifDoc);
        stubUploadPost(Map.of("id", "sarif-3"));
        RestClient.RequestHeadersUriSpec getSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec pollSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.get()).thenReturn(getSpec);
        when(getSpec.uri(anyString(), any(Object.class), any(Object.class), any(Object.class))).thenReturn(headersSpec);
        when(headersSpec.header(anyString(), anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(pollSpec);
        when(pollSpec.body(any(Class.class)))
                .thenThrow(new RuntimeException("transient"))
                .thenReturn(Map.of("processing_status", "complete"));
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        AnalyzeDiffResponse analysis = AnalyzeDiffResponse.builder().title("t").build();

        client.uploadSarif("o", "r", "abc123def456abc123def456abc123def456abcd", "refs/pull/1/head", analysis, 42L);

        verify(pollSpec, times(2)).body(any(Class.class));
    }

    @SuppressWarnings("unchecked")
    private RestClient.RequestBodySpec stubUploadPost(Map<String, Object> response) {
        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec postResponse = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString(), any(Object.class), any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.body(any(Map.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(postResponse);
        when(postResponse.body(any(Class.class))).thenReturn(response);
        lastUploadBodySpec = bodySpec;
        return bodySpec;
    }

    private RestClient.RequestBodySpec lastUploadBodySpec;

    @SuppressWarnings("unchecked")
    private RestClient.ResponseSpec stubPollGet(List<Map<String, Object>> responses) {
        RestClient.RequestHeadersUriSpec getSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec pollSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.get()).thenReturn(getSpec);
        when(getSpec.uri(anyString(), any(Object.class), any(Object.class), any(Object.class))).thenReturn(headersSpec);
        when(headersSpec.header(anyString(), anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(pollSpec);
        Queue<Map<String, Object>> queue = new ArrayDeque<>(responses);
        when(pollSpec.body(any(Class.class))).thenAnswer(invocation -> queue.poll());
        lastPollSpec = pollSpec;
        return pollSpec;
    }

    private RestClient.ResponseSpec lastPollSpec;

    @SuppressWarnings("unchecked")
    @Test
    void uploadSarif_shouldHandleBlankCategoryAndNullRuns() {
        GithubProperties p = props();
        p.getSarif().setCategory("  ");
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        Map<String, Object> sarifDoc = new java.util.LinkedHashMap<>();
        sarifDoc.put("version", "2.1.0");
        sarifDoc.put("runs", null);
        when(sarifService.toSarif(any())).thenReturn(sarifDoc);

        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec postResponse = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString(), any(Object.class), any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.body(any(Map.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(postResponse);
        when(postResponse.body(any(Class.class))).thenReturn(null);

        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        AnalyzeDiffResponse analysis = AnalyzeDiffResponse.builder().title("t").build();

        client.uploadSarif("o", "r", "abc123def456abc123def456abc123def456abcd", "refs/pull/1/head", analysis, 42L);

        verify(bodySpec).body(any(Map.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void uploadSarif_shouldSkipMergeForEmptyRuns() {
        GithubProperties p = props();
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        Map<String, Object> sarifDoc = new LinkedHashMap<>();
        sarifDoc.put("version", "2.1.0");
        sarifDoc.put("runs", new java.util.ArrayList<>());
        when(sarifService.toSarif(any())).thenReturn(sarifDoc);
        stubUploadPost(Map.of());
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        AnalyzeDiffResponse analysis = AnalyzeDiffResponse.builder().title("t").build();

        client.uploadSarif("o", "r", "abc123def456abc123def456abc123def456abcd", "refs/pull/1/head", analysis, 42L);

        verify(lastUploadBodySpec).body(any(Map.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void uploadSarif_shouldReturnOnNullPollStatus() {
        GithubProperties p = props();
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        Map<String, Object> sarifDoc = new LinkedHashMap<>();
        sarifDoc.put("version", "2.1.0");
        sarifDoc.put("runs", null);
        when(sarifService.toSarif(any())).thenReturn(sarifDoc);
        stubUploadPost(Map.of("id", "sarif-null"));
        RestClient.RequestHeadersUriSpec getSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec pollSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.get()).thenReturn(getSpec);
        when(getSpec.uri(anyString(), any(Object.class), any(Object.class), any(Object.class))).thenReturn(headersSpec);
        when(headersSpec.header(anyString(), anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(pollSpec);
        when(pollSpec.body(any(Class.class))).thenReturn(null);
        lastPollSpec = pollSpec;
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        AnalyzeDiffResponse analysis = AnalyzeDiffResponse.builder().title("t").build();

        client.uploadSarif("o", "r", "abc123def456abc123def456abc123def456abcd", "refs/pull/1/head", analysis, 42L);

        verify(lastPollSpec, times(1)).body(any(Class.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void uploadSarif_shouldRestoreInterruptAndReturnOnPollInterrupt() {
        GithubProperties p = props();
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        Map<String, Object> sarifDoc = new LinkedHashMap<>();
        sarifDoc.put("version", "2.1.0");
        sarifDoc.put("runs", null);
        when(sarifService.toSarif(any())).thenReturn(sarifDoc);
        stubUploadPost(Map.of("id", "sarif-int"));
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        AnalyzeDiffResponse analysis = AnalyzeDiffResponse.builder().title("t").build();
        Thread.currentThread().interrupt();
        try {
            client.uploadSarif("o", "r", "abc123def456abc123def456abc123def456abcd", "refs/pull/1/head", analysis, 42L);
        } finally {
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            Thread.interrupted();
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void postReview_shouldCapInlineCommentsAndSkipNonActionableRisks() {
        GithubProperties p = props();
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString(), any(Object.class), any(Object.class), any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.body(any(Map.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(null);
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        List<RiskItem> risks = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            risks.add(new RiskItem("error", "problem-" + i));
        }
        risks.add(new RiskItem("warning", "watch out"));
        risks.add(new RiskItem("note", "fyi"));
        risks.add(new RiskItem("error", null));
        risks.add(new RiskItem("error", "  "));
        AnalyzeDiffResponse full = AnalyzeDiffResponse.builder()
                .risks(risks)
                .touchedFiles(List.of("src/Main.java"))
                .suggestedTests(List.of("t1", "t2"))
                .build();
        AnalyzeDiffResponse bare = AnalyzeDiffResponse.builder()
                .title("t")
                .risks(List.of(new RiskItem("error", "boom")))
                .suggestedTests(List.of())
                .build();

        client.postReview("o", "r", 1, "abc", full, 42L);
        client.postReview("o", "r", 2, "abc", bare, 42L);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(bodySpec, times(2)).body(captor.capture());
        List<Object> firstComments = (List<Object>) captor.getAllValues().get(0).get("comments");
        assertThat(firstComments).hasSize(10);
        assertThat(captor.getAllValues().get(1).containsKey("comments")).isFalse();
    }

    @SuppressWarnings("unchecked")
    @Test
    void uploadSarif_shouldRejectOversizedDocument() {
        GithubProperties p = props();
        stubBuilder();
        when(sarifProperties.getMaxBytes()).thenReturn(10L);
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        Map<String, Object> sarifDoc = new java.util.LinkedHashMap<>();
        sarifDoc.put("version", "2.1.0");
        sarifDoc.put("runs", null);
        when(sarifService.toSarif(any())).thenReturn(sarifDoc);
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        AnalyzeDiffResponse analysis = AnalyzeDiffResponse.builder().title("t").build();

        assertThatThrownBy(() -> client.uploadSarif(
                "o", "r", "abc123def456abc123def456abc123def456abcd", "refs/pull/1/head", analysis, 42L))
                .isInstanceOf(DiffTooLargeException.class);

        verify(restClient, never()).post();
    }

    @Test
    void fetchDiff_noArgShouldUseDefaultInstallationId() {
        GithubProperties p = props();
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        stubFetchDiff("diff");
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        assertThat(client.fetchDiff("o", "r", 1)).isEqualTo("diff");
    }

    private static RestClientResponseException responseException(HttpStatus status) {
        return new HttpClientErrorException(status);
    }

    private RestClient.ResponseSpec stubFetchDiffBody() {
        RestClient.RequestHeadersUriSpec getSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.get()).thenReturn(getSpec);
        when(getSpec.uri(anyString(), any(Object.class), any(Object.class), any(Object.class))).thenReturn(headersSpec);
        when(headersSpec.header(anyString(), anyString())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        return responseSpec;
    }

    @Test
    void fetchDiff_shouldEvictAndRetryOnceOn401() {
        GithubProperties p = props();
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_old", "ghs_new");
        RestClient.ResponseSpec responseSpec = stubFetchDiffBody();
        when(responseSpec.body(any(Class.class)))
                .thenThrow(responseException(HttpStatus.UNAUTHORIZED))
                .thenReturn("recovered".getBytes(StandardCharsets.UTF_8));
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        assertThat(client.fetchDiff("o", "r", 1, 42L)).isEqualTo("recovered");

        verify(authService).evictToken(42L);
        verify(authService, times(2)).getInstallationToken(42L);
    }

    @Test
    void fetchDiff_shouldPropagateSecond401WithoutFurtherRetry() {
        GithubProperties p = props();
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_old", "ghs_new");
        RestClient.ResponseSpec responseSpec = stubFetchDiffBody();
        when(responseSpec.body(any(Class.class)))
                .thenThrow(responseException(HttpStatus.UNAUTHORIZED))
                .thenThrow(responseException(HttpStatus.UNAUTHORIZED));
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        assertThatThrownBy(() -> client.fetchDiff("o", "r", 1, 42L))
                .isInstanceOf(RestClientResponseException.class);

        verify(authService, times(1)).evictToken(42L);
        verify(authService, times(2)).getInstallationToken(42L);
    }

    @Test
    void fetchDiff_shouldNotRetryOnNon401() {
        GithubProperties p = props();
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        RestClient.ResponseSpec responseSpec = stubFetchDiffBody();
        when(responseSpec.body(any(Class.class)))
                .thenThrow(responseException(HttpStatus.INTERNAL_SERVER_ERROR));
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        assertThatThrownBy(() -> client.fetchDiff("o", "r", 1, 42L))
                .isInstanceOf(RestClientResponseException.class);

        verify(authService, never()).evictToken(any());
        verify(authService, times(1)).getInstallationToken(42L);
    }

    @Test
    void constructor_shouldBuildSingleSharedClient() {
        GithubProperties p = props();
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        stubFetchDiff("diff");
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, sarifProperties, objectMapper, restClientBuilder);

        client.fetchDiff("o", "r", 1, 42L);
        client.fetchDiff("o", "r", 2, 42L);

        verify(restClientBuilder, times(1)).build();
    }
}
