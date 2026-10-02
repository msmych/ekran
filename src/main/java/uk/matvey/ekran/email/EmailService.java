package uk.matvey.ekran.email;

/** Application-level email service — the app never talks to Resend directly. */
public interface EmailService {

    void send(String to, String subject, String html, String text);
}
