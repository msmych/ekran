package uk.matvey.ekran.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResendEmailServiceTest {

    private final MockWebServer server = new MockWebServer();
    private ResendEmailService emailService;

    @BeforeEach
    void start() throws Exception {
        server.start();
        emailService = new ResendEmailService(
            HttpClient.newHttpClient(),
            new ObjectMapper(),
            "re_secret_key",
            server.url("/").toString(),
            "ekran <no-reply@ekran.uk>");
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    @Test
    void sendsToResendApiWithBearerTokenAndPayload() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"id\":\"email-id\"}"));

        emailService.send("foo@bar.com", "Sign in to ekran", "<p>html</p>", "text");

        var recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/emails");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer re_secret_key");
        assertThat(recorded.getHeader("Content-Type")).isEqualTo("application/json");
        var payload = new ObjectMapper().readTree(recorded.getBody().readUtf8());
        assertThat(payload.get("from").asText()).isEqualTo("ekran <no-reply@ekran.uk>");
        assertThat(payload.get("to").get(0).asText()).isEqualTo("foo@bar.com");
        assertThat(payload.get("subject").asText()).isEqualTo("Sign in to ekran");
        assertThat(payload.get("html").asText()).isEqualTo("<p>html</p>");
        assertThat(payload.get("text").asText()).isEqualTo("text");
    }

    @Test
    void failureThrowsGenericExceptionWithoutLeakingTheKey() {
        server.enqueue(new MockResponse().setResponseCode(422).setBody("{\"name\":\"validation_error\"}"));

        assertThatThrownBy(() -> emailService.send("foo@bar.com", "s", "h", "t"))
            .isInstanceOf(EmailException.class)
            .hasMessageContaining("HTTP 422")
            .hasMessageNotContaining("re_secret_key");
    }

    @Test
    void serverErrorThrowsGenericException() {
        server.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> emailService.send("foo@bar.com", "s", "h", "t"))
            .isInstanceOf(EmailException.class)
            .hasMessageNotContaining("re_secret_key");
    }

    @Test
    void connectionFailureThrowsGenericException() throws Exception {
        var port = server.getPort();
        server.shutdown();

        assertThatThrownBy(() -> emailService.send("foo@bar.com", "s", "h", "t"))
            .isInstanceOf(EmailException.class)
            .hasMessageNotContaining("re_secret_key");
    }
}