package uk.matvey.ekran.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Resend HTTP API implementation (https://resend.com/docs/api-reference). */
public class ResendEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailService.class);

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final String fromEmail;

    public ResendEmailService(HttpClient httpClient, ObjectMapper objectMapper, String apiKey, String baseUrl, String fromEmail) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.fromEmail = fromEmail;
    }

    @Override
    public void send(String to, String subject, String html, String text) {
        var payload = payload(to, subject, html, text);
        var request = HttpRequest.newBuilder(URI.create(baseUrl + "/emails"))
            .timeout(SEND_TIMEOUT)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();
        try {
            var response = httpClient.send(request, BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return;
            }
            log.warn("Resend send failed: status={} body={}", response.statusCode(), response.body());
            throw new EmailException("Email delivery failed (HTTP " + response.statusCode() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmailException("Email delivery interrupted", e);
        } catch (IOException e) {
            log.warn("Resend request failed: {}", e.getMessage());
            throw new EmailException("Email delivery failed", e);
        }
    }

    private String payload(String to, String subject, String html, String text) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                "from", fromEmail,
                "to", List.of(to),
                "subject", subject,
                "html", html,
                "text", text));
        } catch (IOException e) {
            throw new EmailException("Cannot build email request", e);
        }
    }
}