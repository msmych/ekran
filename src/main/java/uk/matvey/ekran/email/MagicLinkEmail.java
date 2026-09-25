package uk.matvey.ekran.email;

import java.time.Duration;

/** The magic-link email: subject, minimal HTML body with a plain-text fallback. */
public final class MagicLinkEmail {

    private MagicLinkEmail() {
    }

    public static String subject() {
        return "Sign in to ekran";
    }

    public static String html(String link, Duration ttl) {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <body style="margin:0;padding:24px;background:#fafafa;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;color:#1a1a1a;line-height:1.5;">
              <div style="max-width:420px;margin:0 auto;background:#fff;border:1px solid #e2e2e2;border-radius:8px;padding:32px;">
                <h2 style="margin:0 0 12px;font-size:20px;">Sign in to ekran</h2>
                <p style="margin:0 0 24px;">Click the button below to sign in.</p>
                <p style="margin:0 0 24px;">
                  <a href="%s" style="display:inline-block;background:#b3261e;color:#fff;text-decoration:none;padding:10px 20px;border-radius:8px;font-size:15px;">Sign in to ekran</a>
                </p>
                <p style="margin:0 0 8px;font-size:13px;color:#6b6b6b;">This link expires in %d minutes and can only be used once.</p>
                <p style="margin:0;font-size:13px;color:#6b6b6b;">If the button doesn't work, open this URL:<br>
                  <a href="%s" style="color:#6b6b6b;word-break:break-all;">%s</a>
                </p>
              </div>
            </body>
            </html>
            """.formatted(link, ttl.toMinutes(), link, link);
    }

    public static String text(String link, Duration ttl) {
        return """
            Sign in to ekran

Click the button below to sign in:
%s

This link expires in %d minutes and can only be used once.
""".formatted(link, ttl.toMinutes());
    }
}