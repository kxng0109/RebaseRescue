package io.github.kxng0109.rebaserescue.github;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.RejectedExecutionException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "github.enabled=true",
        "github.webhook.secret=test-secret-123",
        "prcopilot.auth.api-key=test-key",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri="
})
class GithubWebhookControllerMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeliveryDedupStore dedupStore;

    @MockitoBean
    private GithubWebhookService webhookService;

    @MockitoBean
    private GithubAppAuthService authService;

    @MockitoBean
    private GithubApiClient apiClient;

    private static String hmac(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void handle_shouldReturn400WhenDeliveryMissing() throws Exception {
        mockMvc.perform(post("/api/webhooks/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void handle_shouldReturn400WhenDeliveryBlank() throws Exception {
        mockMvc.perform(post("/api/webhooks/github")
                        .header("X-GitHub-Delivery", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        verify(webhookService, never()).handleAsync(anyString(), anyString(), anyString());
    }

    @Test
    void handle_shouldIgnoreMissingEvent() throws Exception {
        String body = "{\"action\":\"opened\"}";
        String sig = hmac("test-secret-123", body);
        mockMvc.perform(post("/api/webhooks/github")
                        .header("X-GitHub-Delivery", "delivery-no-event")
                        .header("X-Hub-Signature-256", sig)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        verify(webhookService, never()).handleAsync(anyString(), anyString(), anyString());
    }

    @Test
    void handle_shouldReturn403WhenSignatureInvalid() throws Exception {
        String body = "{\"action\":\"opened\"}";
        mockMvc.perform(post("/api/webhooks/github")
                        .header("X-GitHub-Delivery", "delivery-1")
                        .header("X-Hub-Signature-256", "sha256=deadbeef")
                        .header("X-GitHub-Event", "pull_request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void handle_shouldReturn200ForPing() throws Exception {
        String body = "{}";
        String sig = hmac("test-secret-123", body);
        mockMvc.perform(post("/api/webhooks/github")
                        .header("X-GitHub-Delivery", "delivery-ping")
                        .header("X-Hub-Signature-256", sig)
                        .header("X-GitHub-Event", "ping")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().string("pong"));
        verify(webhookService, never()).handleAsync(anyString(), anyString(), anyString());
    }

    @Test
    void handle_shouldReturn200ForIgnoredEvent() throws Exception {
        String body = "{\"action\":\"opened\"}";
        String sig = hmac("test-secret-123", body);
        mockMvc.perform(post("/api/webhooks/github")
                        .header("X-GitHub-Delivery", "delivery-2")
                        .header("X-Hub-Signature-256", sig)
                        .header("X-GitHub-Event", "push")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        verify(webhookService, never()).handleAsync(anyString(), anyString(), anyString());
    }

    @Test
    void handle_shouldReturn200ForDuplicate() throws Exception {
        String body = "{\"action\":\"opened\"}";
        String sig = hmac("test-secret-123", body);
        when(dedupStore.tryClaim("delivery-dup")).thenReturn(false);
        mockMvc.perform(post("/api/webhooks/github")
                        .header("X-GitHub-Delivery", "delivery-dup")
                        .header("X-Hub-Signature-256", sig)
                        .header("X-GitHub-Event", "pull_request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().string("duplicate"));
    }

    @Test
    void handle_shouldReturn202ForValidPullRequest() throws Exception {
        String body = """
                {"action":"opened","pull_request":{"number":1,"head":{"sha":"abc"},"base":{"ref":"main"}},"repository":{"name":"r","full_name":"o/r","owner":{"login":"o"}}}
                """;
        String sig = hmac("test-secret-123", body);
        when(dedupStore.tryClaim("delivery-ok")).thenReturn(true);
        mockMvc.perform(post("/api/webhooks/github")
                        .header("X-GitHub-Delivery", "delivery-ok")
                        .header("X-Hub-Signature-256", sig)
                        .header("X-GitHub-Event", "pull_request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());
        verify(webhookService).handleAsync(eq("delivery-ok"), eq("pull_request"), anyString());
    }

    @Test
    void handle_shouldReturn429WhenExecutorSaturated() throws Exception {
        String body = """
                {"action":"opened","pull_request":{"number":1,"head":{"sha":"abc"},"base":{"ref":"main"}},"repository":{"name":"r","full_name":"o/r","owner":{"login":"o"}}}
                """;
        String sig = hmac("test-secret-123", body);
        when(dedupStore.tryClaim("delivery-busy")).thenReturn(true);
        doThrow(new RejectedExecutionException("saturated"))
                .when(webhookService).handleAsync(eq("delivery-busy"), eq("pull_request"), anyString());
        mockMvc.perform(post("/api/webhooks/github")
                        .header("X-GitHub-Delivery", "delivery-busy")
                        .header("X-Hub-Signature-256", sig)
                        .header("X-GitHub-Event", "pull_request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests());
    }
}
