package io.github.kxng0109.aiprcopilot.github;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "github.enabled=true",
        "github.webhook.secret=test-secret-123",
        "github.webhook.max-request-bytes=1024",
        "prcopilot.auth.api-key=test-key",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri="
})
class GithubWebhookSizeLimitMvcTest {

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

    @Test
    void handle_shouldReturn413WhenWebhookBodyExceedsCap() throws Exception {
        String oversized = "{\"data\":\"" + "x".repeat(2000) + "\"}";
        mockMvc.perform(post("/api/webhooks/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Delivery", "delivery-big")
                        .header("X-GitHub-Event", "pull_request")
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge());
    }
}
