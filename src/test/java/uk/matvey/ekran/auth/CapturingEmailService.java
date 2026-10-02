package uk.matvey.ekran.auth;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import uk.matvey.ekran.email.EmailService;

/** Captures outgoing emails instead of sending them. */
public class CapturingEmailService implements EmailService {

    public record SentEmail(String to, String subject, String html, String text) {
    }

    public final List<SentEmail> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(String to, String subject, String html, String text) {
        sent.add(new SentEmail(to, subject, html, text));
    }

    public Optional<SentEmail> last() {
        return sent.isEmpty() ? Optional.empty() : Optional.of(sent.getLast());
    }
}
