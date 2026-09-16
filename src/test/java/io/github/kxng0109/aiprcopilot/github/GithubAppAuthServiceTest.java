package io.github.kxng0109.aiprcopilot.github;

import com.nimbusds.jwt.SignedJWT;
import io.github.kxng0109.aiprcopilot.config.GithubProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GithubAppAuthServiceTest {

    @Mock
    private RestClient.Builder restClientBuilder;

    @Mock
    private RestClient restClient;

    @TempDir
    Path tempDir;

    private static String generatePem() throws Exception {
        return generatePem(2048);
    }

    private static String generatePem(int bits) throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(bits);
        KeyPair pair = gen.generateKeyPair();
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(pair.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }

    private GithubProperties propsWithPem(String pem, String appId, String clientId, Long installationId) {
        GithubProperties props = new GithubProperties();
        props.setEnabled(true);
        props.getApp().setAppId(appId);
        props.getApp().setClientId(clientId);
        props.getApp().setPrivateKey(pem);
        props.getApp().setInstallationId(installationId);
        props.getApi().setBaseUrl("https://api.github.com");
        props.getApi().setApiVersion("2026-03-10");
        return props;
    }

    private void stubBuilder() {
        lenient().when(restClientBuilder.baseUrl(anyString())).thenReturn(restClientBuilder);
        lenient().when(restClientBuilder.defaultHeader(anyString(), anyString())).thenReturn(restClientBuilder);
        lenient().when(restClientBuilder.build()).thenReturn(restClient);
    }

    @Test
    void createJwt_shouldSignWithClientIdPreferred(@TempDir Path dir) throws Exception {
        String pem = generatePem();
        GithubProperties props = propsWithPem(pem, "123", "Iv1_client123", 1L);
        stubBuilder();
        GithubAppAuthService service = new GithubAppAuthService(props, restClientBuilder);

        String jwt = service.createJwt();

        SignedJWT parsed = SignedJWT.parse(jwt);
        assertThat(parsed.getJWTClaimsSet().getIssuer()).isEqualTo("Iv1_client123");
        assertThat(parsed.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(parsed.getJWTClaimsSet().getExpirationTime()).isAfter(parsed.getJWTClaimsSet().getIssueTime());
    }

    @Test
    void createJwt_shouldFallbackToAppIdWhenClientIdBlank() throws Exception {
        String pem = generatePem();
        GithubProperties props = propsWithPem(pem, "999", "  ", 1L);
        stubBuilder();
        GithubAppAuthService service = new GithubAppAuthService(props, restClientBuilder);

        String jwt = service.createJwt();

        assertThat(SignedJWT.parse(jwt).getJWTClaimsSet().getIssuer()).isEqualTo("999");
    }

    @Test
    void createJwt_shouldHandleEscapedNewlines() throws Exception {
        String pem = generatePem();
        String escaped = pem.replace("\n", "\\n");
        GithubProperties props = propsWithPem(escaped, "123", null, 1L);
        stubBuilder();
        GithubAppAuthService service = new GithubAppAuthService(props, restClientBuilder);

        assertThat(service.createJwt()).isNotBlank();
    }

    @Test
    void createJwt_shouldHandleFilePrefix(@TempDir Path dir) throws Exception {
        String pem = generatePem();
        Path file = dir.resolve("key.pem");
        Files.writeString(file, pem, StandardCharsets.UTF_8);
        GithubProperties props = propsWithPem("file:" + file.toString(), "123", null, 1L);
        stubBuilder();
        GithubAppAuthService service = new GithubAppAuthService(props, restClientBuilder);

        assertThat(service.createJwt()).isNotBlank();
    }

    @Test
    void createJwt_shouldThrowWhenNoIssuer() throws Exception {
        String pem = generatePem();
        GithubProperties props = propsWithPem(pem, "  ", " ", 1L);
        stubBuilder();
        GithubAppAuthService service = new GithubAppAuthService(props, restClientBuilder);

        assertThatThrownBy(service::createJwt).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createJwt_shouldThrowWhenPrivateKeyBlank() throws Exception {
        GithubProperties props = propsWithPem("  ", "123", null, 1L);
        stubBuilder();
        GithubAppAuthService service = new GithubAppAuthService(props, restClientBuilder);

        assertThatThrownBy(service::createJwt).isInstanceOf(IllegalStateException.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    void getInstallationToken_shouldCacheAndEvict() throws Exception {
        String pem = generatePem();
        GithubProperties props = propsWithPem(pem, "123", null, 42L);
        stubBuilder();
        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString(), any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(any(Class.class))).thenReturn(Map.of("token", "ghs_test123", "expires_at", "2099-01-01T00:00:00Z"));

        GithubAppAuthService service = new GithubAppAuthService(props, restClientBuilder);

        String first = service.getInstallationToken(42L);
        String second = service.getInstallationToken(42L);
        assertThat(first).isEqualTo("ghs_test123");
        assertThat(second).isEqualTo("ghs_test123");

        service.evictToken(42L);
        String third = service.getInstallationToken(42L);
        assertThat(third).isEqualTo("ghs_test123");
    }

    @Test
    void getInstallationToken_shouldThrowWhenInstallationIdNull() throws Exception {
        String pem = generatePem();
        GithubProperties props = propsWithPem(pem, "123", null, 42L);
        stubBuilder();
        GithubAppAuthService service = new GithubAppAuthService(props, restClientBuilder);

        assertThatThrownBy(() -> service.getInstallationToken(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getInstallationToken_noArg_shouldUseConfiguredInstallationId() throws Exception {
        String pem = generatePem();
        GithubProperties props = propsWithPem(pem, "123", null, 99L);
        stubBuilder();
        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString(), any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(any(Class.class))).thenReturn(Map.of("token", "ghs_abc", "expires_at", "2099-01-01T00:00:00Z"));

        GithubAppAuthService service = new GithubAppAuthService(props, restClientBuilder);

        assertThat(service.getInstallationToken()).isEqualTo("ghs_abc");
    }

    @Test
    void getInstallationToken_shouldThrowWhenResponseMissingToken() throws Exception {
        String pem = generatePem();
        GithubProperties props = propsWithPem(pem, "123", null, 42L);
        stubBuilder();
        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString(), any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(any(Class.class))).thenReturn(Map.of("expires_at", "2099-01-01T00:00:00Z"));

        GithubAppAuthService service = new GithubAppAuthService(props, restClientBuilder);

        assertThatThrownBy(() -> service.getInstallationToken(42L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void getInstallationToken_shouldHandleInvalidExpiresAtGracefully() throws Exception {
        String pem = generatePem();
        GithubProperties props = propsWithPem(pem, "123", null, 42L);
        stubBuilder();
        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString(), any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(any(Class.class))).thenReturn(Map.of("token", "ghs_abc", "expires_at", "not-a-date"));

        GithubAppAuthService service = new GithubAppAuthService(props, restClientBuilder);

        assertThat(service.getInstallationToken(42L)).isEqualTo("ghs_abc");
    }

    @SuppressWarnings("unchecked")
    @Test
    void getInstallationToken_shouldRefreshStaleCacheEntry() throws Exception {
        String pem = generatePem();
        GithubProperties props = propsWithPem(pem, "123", null, 42L);
        stubBuilder();
        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString(), any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(any(Class.class))).thenReturn(Map.of("token", "ghs_old", "expires_at", "2000-01-01T00:00:00Z"))
                .thenReturn(Map.of("token", "ghs_new", "expires_at", "2099-01-01T00:00:00Z"));

        GithubAppAuthService service = new GithubAppAuthService(props, restClientBuilder);

        assertThat(service.getInstallationToken(42L)).isEqualTo("ghs_old");
        assertThat(service.getInstallationToken(42L)).isEqualTo("ghs_new");
    }

    @SuppressWarnings("unchecked")
    @Test
    void getInstallationToken_shouldFallBackToTtlWhenExpiresAtMissing() throws Exception {
        String pem = generatePem();
        GithubProperties props = propsWithPem(pem, "123", null, 42L);
        stubBuilder();
        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString(), any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(any(Class.class))).thenReturn(Map.of("token", "ghs_noexp"));

        GithubAppAuthService service = new GithubAppAuthService(props, restClientBuilder);

        assertThat(service.getInstallationToken(42L)).isEqualTo("ghs_noexp");
        assertThat(service.getInstallationToken(42L)).isEqualTo("ghs_noexp");
    }

    @Test
    void createJwt_shouldThrowWhenNeitherClientIdNorAppId() throws Exception {
        String pem = generatePem();
        GithubProperties props = propsWithPem(pem, "  ", "  ", 1L);
        stubBuilder();
        GithubAppAuthService service = new GithubAppAuthService(props, restClientBuilder);

        assertThatThrownBy(service::createJwt).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void createJwt_shouldThrowWhenPrivateKeyNull() throws Exception {
        GithubProperties props = propsWithPem(null, "123", null, 1L);
        stubBuilder();
        GithubAppAuthService service = new GithubAppAuthService(props, restClientBuilder);

        assertThatThrownBy(service::createJwt).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createJwt_shouldRejectNonRsaKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(256);
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(gen.generateKeyPair().getPrivate().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
        GithubProperties props = propsWithPem(pem, "123", null, 1L);
        stubBuilder();
        GithubAppAuthService service = new GithubAppAuthService(props, restClientBuilder);

        assertThatThrownBy(service::createJwt).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createJwt_shouldThrowWhenPrivateKeyFileNotFound() throws Exception {
        GithubProperties props = propsWithPem("file:/no/such/file.pem", "123", null, 1L);
        stubBuilder();
        GithubAppAuthService service = new GithubAppAuthService(props, restClientBuilder);

        assertThatThrownBy(service::createJwt).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createJwt_shouldThrowWhenPemInvalid() throws Exception {
        GithubProperties props = propsWithPem("not-a-pem", "123", null, 1L);
        stubBuilder();
        GithubAppAuthService service = new GithubAppAuthService(props, restClientBuilder);

        assertThatThrownBy(service::createJwt).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void evictToken_shouldHandleNullGracefully() throws Exception {
        String pem = generatePem();
        GithubProperties props = propsWithPem(pem, "123", null, 1L);
        stubBuilder();
        GithubAppAuthService service = new GithubAppAuthService(props, restClientBuilder);

        service.evictToken(null);
    }

    @Test
    void createJwt_shouldRejectKeysBelow2048Bits() throws Exception {
        String pem = generatePem(1024);
        GithubProperties props = propsWithPem(pem, "123", null, 1L);
        stubBuilder();
        GithubAppAuthService service = new GithubAppAuthService(props, restClientBuilder);

        assertThatThrownBy(service::createJwt)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2048");
    }

    @Test
    void constructor_shouldRejectNonAllowlistedBaseUrl() throws Exception {
        String pem = generatePem();
        GithubProperties props = propsWithPem(pem, "123", null, 1L);
        props.getApi().setBaseUrl("http://evil.example.com");
        stubBuilder();

        assertThatThrownBy(() -> new GithubAppAuthService(props, restClientBuilder))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getInstallationToken_noArg_shouldThrowWhenNotConfigured() throws Exception {
        String pem = generatePem();
        GithubProperties props = propsWithPem(pem, "123", null, null);
        stubBuilder();
        GithubAppAuthService service = new GithubAppAuthService(props, restClientBuilder);

        assertThatThrownBy(service::getInstallationToken).isInstanceOf(IllegalStateException.class);
    }
}
