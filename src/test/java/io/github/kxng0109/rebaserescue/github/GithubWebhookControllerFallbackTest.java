package io.github.kxng0109.rebaserescue.github;

import io.github.kxng0109.rebaserescue.config.GithubProperties;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class GithubWebhookControllerFallbackTest {

    @Mock
    private GithubProperties properties;

    @Mock
    private DeliveryDedupStore dedupStore;

    @Mock
    private GithubWebhookService webhookService;

    @Test
    void handleRateLimited_shouldReturn429() {
        GithubWebhookController controller = new GithubWebhookController(properties, dedupStore, webhookService);
        RequestNotPermitted denied = mock(RequestNotPermitted.class);

        ResponseEntity<String> response =
                controller.handleRateLimited(new byte[0], null, null, null, null, denied);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isEqualTo("rate limited");
    }
}
