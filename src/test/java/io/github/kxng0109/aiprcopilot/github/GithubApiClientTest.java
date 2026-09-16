package io.github.kxng0109.aiprcopilot.github;

import io.github.kxng0109.aiprcopilot.api.dto.AnalyzeDiffResponse;
import io.github.kxng0109.aiprcopilot.api.dto.RiskItem;
import io.github.kxng0109.aiprcopilot.config.GithubProperties;
import io.github.kxng0109.aiprcopilot.service.SarifService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GithubApiClientTest {

    @Mock
    private GithubAppAuthService authService;

    @Mock
    private SarifService sarifService;

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
        p.getSarif().setCategory("ai-pr-copilot");
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
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, objectMapper, restClientBuilder);

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
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, objectMapper, restClientBuilder);

        assertThat(client.fetchDiff("o", "r", 1, 42L)).isNotBlank();
    }

    @Test
    void fetchDiff_shouldFallbackToDefaultBaseUrlWhenBlank() {
        GithubProperties p = props();
        p.getApi().setBaseUrl("  ");
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        stubFetchDiff("diff --git a/F.java");
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, objectMapper, restClientBuilder);

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
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, objectMapper, restClientBuilder);

        assertThat(client.fetchDiff("o", "r", 1, 42L)).isEmpty();
    }

    @Test
    void fetchDiff_shouldThrowWhenInstallationMissing() {
        GithubProperties p = props();
        p.getApp().setInstallationId(null);
        stubBuilder();
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, objectMapper, restClientBuilder);

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
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, objectMapper, restClientBuilder);

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
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, objectMapper, restClientBuilder);

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
        Map<String, Object> sarifDoc = Map.of("version", "2.1.0", "runs", List.of(Map.of("tool", Map.of("driver", Map.of("name", "ai-pr-copilot")))));
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

        GithubApiClient client = new GithubApiClient(p, authService, sarifService, objectMapper, restClientBuilder);

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
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, objectMapper, restClientBuilder);

        AnalyzeDiffResponse analysis = AnalyzeDiffResponse.builder()
                .title("t")
                .risks(null)
                .touchedFiles(null)
                .build();

        client.postReview("o", "r", 1, "  ", analysis, 42L);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(bodySpec).body(captor.capture());
        assertThat(captor.getValue().containsKey("commit_id")).isFalse();
        assertThat(captor.getValue().containsKey("comments")).isFalse();
    }

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

        GithubApiClient client = new GithubApiClient(p, authService, sarifService, objectMapper, restClientBuilder);

        AnalyzeDiffResponse analysis = AnalyzeDiffResponse.builder().title("t").build();

        client.uploadSarif("o", "r", "abc123def456abc123def456abc123def456abcd", "refs/pull/1/head", analysis, 42L);

        verify(bodySpec).body(any(Map.class));
    }

    @Test
    void fetchDiff_noArgShouldUseDefaultInstallationId() {
        GithubProperties p = props();
        stubBuilder();
        when(authService.getInstallationToken(42L)).thenReturn("ghs_test");
        stubFetchDiff("diff");
        GithubApiClient client = new GithubApiClient(p, authService, sarifService, objectMapper, restClientBuilder);

        assertThat(client.fetchDiff("o", "r", 1)).isEqualTo("diff");
    }
}
