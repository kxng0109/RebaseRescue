package io.github.kxng0109.aiprcopilot.github;

import io.github.kxng0109.aiprcopilot.config.GithubProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class GithubWebhookControllerHmacTest {

    @Mock
    private DeliveryDedupStore dedupStore;

    @Mock
    private GithubWebhookService webhookService;

    private GithubWebhookController controllerWithSecret(String secret) {
        GithubProperties props = new GithubProperties();
        props.getWebhook().setSecret(secret);
        return new GithubWebhookController(props, dedupStore, webhookService);
    }

    @Test
    void verifySignature_shouldMatchGithubTestVector() throws Exception {
        // GitHub docs vector: secret "It's a Secret to Everybody", payload "Hello, World!"
        String secret = "It's a Secret to Everybody";
        byte[] body = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String hex = HexFormat.of().formatHex(mac.doFinal(body));
        String header = "sha256=" + hex;

        GithubWebhookController controller = controllerWithSecret(secret);

        assertThat(controller.verifySignature(body, header)).isTrue();
        assertThat(header).isEqualTo("sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17");
    }

    @Test
    void verifySignature_shouldRejectWrongSecret() {
        GithubWebhookController controller = controllerWithSecret("correct-secret");
        byte[] body = "Hello, World!".getBytes(StandardCharsets.UTF_8);

        assertThat(controller.verifySignature(body, "sha256=deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef")).isFalse();
    }

    @Test
    void verifySignature_shouldRejectMissingHeader() {
        GithubWebhookController controller = controllerWithSecret("secret");

        assertThat(controller.verifySignature("body".getBytes(StandardCharsets.UTF_8), null)).isFalse();
        assertThat(controller.verifySignature("body".getBytes(StandardCharsets.UTF_8), "sha1=abc")).isFalse();
        assertThat(controller.verifySignature("body".getBytes(StandardCharsets.UTF_8), "sha256=")).isFalse();
        assertThat(controller.verifySignature("body".getBytes(StandardCharsets.UTF_8), "sha256=   ")).isFalse();
    }

    @Test
    void verifySignature_shouldReturnFalseWhenSecretBlank() {
        GithubWebhookController controller = controllerWithSecret("  ");

        assertThat(controller.verifySignature("body".getBytes(StandardCharsets.UTF_8), "sha256=abc")).isFalse();
    }

    @Test
    void verifySignature_shouldReturnFalseWhenSecretNull() {
        GithubWebhookController controller = controllerWithSecret(null);

        assertThat(controller.verifySignature("body".getBytes(StandardCharsets.UTF_8), "sha256=abc")).isFalse();
    }

    @Test
    void verifySignature_shouldBeCaseInsensitiveOnHex() throws Exception {
        String secret = "mysecret";
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String hex = HexFormat.of().formatHex(mac.doFinal(body)).toUpperCase();
        String header = "sha256=" + hex;

        GithubWebhookController controller = controllerWithSecret(secret);

        assertThat(controller.verifySignature(body, header)).isTrue();
    }
}
