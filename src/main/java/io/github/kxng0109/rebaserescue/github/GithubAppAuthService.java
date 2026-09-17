package io.github.kxng0109.rebaserescue.github;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.kxng0109.rebaserescue.config.GithubProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * GitHub App authentication: RS256 JWT → installation access token.
 *
 * <p>JWT: {@code iss} = clientId (preferred) or appId, {@code iat} = now-60s,
 * {@code exp} = now+9m (inside GitHub's 10m ceiling), signed with the App's
 * PEM private key (PKCS#1 or PKCS#8, raw or {@code file:} path). Installation
 * tokens are cached for 55m (GitHub TTL is 1h) and refreshed on 401.</p>
 *
 * <p>All secrets are env-injected and never logged. PEM loading supports
 * {@code \n}-escaped env values.</p>
 */
@Service
@Slf4j
public class GithubAppAuthService {

    private final GithubProperties properties;
    private final RestClient restClient;
    private final Cache<Long, CachedToken> tokenCache;

    public GithubAppAuthService(GithubProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        String baseUrl = GithubApiHostPolicy.normalize(
                properties.getApi().getBaseUrl(), properties.getApi().getAllowedHosts());
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", properties.getApi().getApiVersion())
                .build();
        this.tokenCache = Caffeine.newBuilder()
                .maximumSize(properties.getApi().getTokenCacheMaxSize())
                .expireAfterWrite(properties.getApi().getTokenCacheTtl())
                .recordStats()
                .build();
    }

    /**
     * Creates a fresh signed JWT for the GitHub App.
     *
     * @return compact serialized JWT, never {@code null}
     * @throws IllegalStateException when required config is missing or PEM is invalid
     */
    public String createJwt() {
        String issuer = issuer();
        PrivateKey privateKey = loadPrivateKey();
        try {
            long now = System.currentTimeMillis();
            Date iat = new Date(now - 60_000L);
            Date exp = new Date(now + 9 * 60 * 1000L);
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .issueTime(iat)
                    .expirationTime(exp)
                    .jwtID(UUID.randomUUID().toString())
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims);
            jwt.sign(new RSASSASigner(privateKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign GitHub App JWT", e);
        }
    }

    /**
     * Returns a cached or freshly minted installation token for the given
     * installation. Single-installation deployments may call the no-arg
     * overload.
     *
     * @param installationId the installation identifier, must not be {@code null}
     * @return installation token, never {@code null}
     * @throws IllegalStateException when GitHub is disabled or config is incomplete
     */
    public String getInstallationToken(Long installationId) {
        if (installationId == null) {
            throw new IllegalArgumentException("installationId must not be null");
        }
        CachedToken cached = tokenCache.getIfPresent(installationId);
        if (cached != null && cached.expiresAt().isAfter(Instant.now().plus(Duration.ofMinutes(2)))) {
            return cached.token();
        }
        String jwt = createJwt();
        Map<?, ?> response = restClient.post()
                .uri("/app/installations/{id}/access_tokens", installationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt)
                .contentType(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(Map.class);
        if (response == null || response.get("token") == null) {
            throw new IllegalStateException("GitHub installation token response missing token");
        }
        String token = response.get("token").toString();
        Object expiresAtRaw = response.get("expires_at");
        Instant expiresAt = null;
        if (expiresAtRaw != null) {
            try {
                expiresAt = Instant.parse(expiresAtRaw.toString());
            } catch (Exception e) {
                log.warn("Could not parse GitHub token expires_at '{}', using cache TTL", expiresAtRaw);
            }
        }
        if (expiresAt == null) {
            expiresAt = Instant.now().plus(properties.getApi().getTokenCacheTtl());
        }
        tokenCache.put(installationId, new CachedToken(token, expiresAt));
        log.debug("GitHub installation token minted for installation {} (expires {})", installationId, expiresAt);
        return token;
    }

    /**
     * Convenience for single-installation mode using the configured
     * {@code github.app.installation-id}.
     *
     * @return installation token, never {@code null}
     */
    public String getInstallationToken() {
        Long installationId = properties.getApp().getInstallationId();
        if (installationId == null) {
            throw new IllegalStateException("github.app.installation-id is not configured");
        }
        return getInstallationToken(installationId);
    }

    /**
     * Evicts a cached token, forcing a refresh on next use. Call on 401 from
     * GitHub API calls.
     *
     * @param installationId the installation identifier, may be {@code null}
     */
    public void evictToken(Long installationId) {
        if (installationId != null) {
            tokenCache.invalidate(installationId);
            log.debug("Evicted GitHub installation token for {}", installationId);
        }
    }

    private String issuer() {
        String clientId = properties.getApp().getClientId();
        if (clientId != null && !clientId.isBlank()) {
            return clientId.trim();
        }
        String appId = properties.getApp().getAppId();
        if (appId != null && !appId.isBlank()) {
            return appId.trim();
        }
        throw new IllegalStateException("github.app.client-id or github.app.app-id must be configured when github.enabled=true");
    }

    private PrivateKey loadPrivateKey() {
        String raw = properties.getApp().getPrivateKey();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("github.app.private-key must be configured when github.enabled=true");
        }
        String pem;
        String trimmed = raw.trim();
        if (trimmed.startsWith("file:")) {
            String path = trimmed.substring(5).trim();
            try {
                pem = Files.readString(Path.of(path), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read GitHub App private key file: " + path, e);
            }
        } else {
            pem = trimmed.contains("\\n") ? trimmed.replace("\\n", "\n") : trimmed;
        }
        PrivateKey privateKey;
        try {
            JWK jwk = JWK.parseFromPEMEncodedObjects(pem);
            if (!(jwk instanceof RSAKey parsed)) {
                throw new IllegalStateException("GitHub App private key is not an RSA key");
            }
            privateKey = parsed.toPrivateKey();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse GitHub App private key PEM", e);
        }
        if (privateKey instanceof RSAPrivateKey rsaPrivateKey
                && rsaPrivateKey.getModulus().bitLength() < 2048) {
            throw new IllegalStateException(
                    "GitHub App private key must be at least 2048 bits");
        }
        return privateKey;
    }

    private record CachedToken(String token, Instant expiresAt) {}
}
