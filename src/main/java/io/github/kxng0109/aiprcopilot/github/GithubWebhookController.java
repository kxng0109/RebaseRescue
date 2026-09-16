package io.github.kxng0109.aiprcopilot.github;

import io.github.kxng0109.aiprcopilot.config.GithubProperties;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.RejectedExecutionException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public webhook endpoint for GitHub App deliveries.
 *
 * <p>Path is outside {@code /api/v1/**} so it bypasses the
 * {@code ApiKeyAuthFilter}; HMAC via {@code X-Hub-Signature-256} is the
 * credential. Verifies the signature over the exact raw bytes, checks the
 * {@code X-GitHub-Delivery} dedup store, then enqueues background analysis
 * and returns 202 within GitHub's 10s deadline. Handles {@code ping}
 * deliveries with a plain 200.</p>
 */
@RestController
@RequestMapping("/api/webhooks/github")
@RequiredArgsConstructor
@Slf4j
public class GithubWebhookController {

    private final GithubProperties properties;
    private final DeliveryDedupStore dedupStore;
    private final GithubWebhookService webhookService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RateLimiter(name = "github-webhook", fallbackMethod = "handleRateLimited")
    public ResponseEntity<String> handle(
            @RequestBody byte[] rawBody,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(value = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            HttpServletRequest request) throws IOException {

        if (deliveryId == null || deliveryId.isBlank()) {
            log.warn("GitHub webhook: missing X-GitHub-Delivery");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Missing X-GitHub-Delivery");
        }
        String trimmedDeliveryId = deliveryId.trim();

        if (!verifySignature(rawBody, signature)) {
            log.warn("GitHub webhook: signature mismatch for delivery {}", trimmedDeliveryId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid signature");
        }

        if ("ping".equals(event)) {
            log.info("GitHub webhook ping delivery {}: pong", trimmedDeliveryId);
            return ResponseEntity.ok("pong");
        }

        if (event == null || !"pull_request".equals(event)) {
            log.debug("GitHub webhook delivery {}: ignoring event {}", trimmedDeliveryId, event);
            return ResponseEntity.ok("ignored event " + event);
        }

        if (!dedupStore.tryClaim(trimmedDeliveryId)) {
            log.info("GitHub webhook delivery {}: duplicate/replay dropped", trimmedDeliveryId);
            return ResponseEntity.ok("duplicate");
        }

        String payload = new String(rawBody, StandardCharsets.UTF_8);
        try {
            webhookService.handleAsync(trimmedDeliveryId, event, payload);
        } catch (RejectedExecutionException e) {
            log.warn("GitHub webhook delivery {}: executor saturated, rejecting", trimmedDeliveryId);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("executor saturated");
        }
        log.info("GitHub webhook delivery {}: accepted {} for async processing", trimmedDeliveryId, event);
        return ResponseEntity.accepted().body("accepted " + trimmedDeliveryId);
    }

    @SuppressWarnings("unused")
    ResponseEntity<String> handleRateLimited(byte[] rawBody, String signature, String deliveryId, String event,
            HttpServletRequest request, RequestNotPermitted ex) {
        log.warn("GitHub webhook: rate limit exceeded");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("rate limited");
    }

    boolean verifySignature(byte[] body, String headerValue) {
        String secret = properties.getWebhook().getSecret();
        if (secret == null || secret.isBlank()) {
            log.error("GitHub webhook secret not configured (github.webhook.secret)");
            return false;
        }
        if (headerValue == null || !headerValue.startsWith("sha256=")) {
            return false;
        }
        String providedHex = headerValue.substring(7).trim();
        if (providedHex.isEmpty()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = mac.doFinal(body);
            String computedHex = HexFormat.of().formatHex(computed);
            return MessageDigest.isEqual(
                    computedHex.getBytes(StandardCharsets.US_ASCII),
                    providedHex.toLowerCase().getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("GitHub webhook HMAC failure", e);
            return false;
        }
    }
}
