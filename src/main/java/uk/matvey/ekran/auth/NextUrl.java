package uk.matvey.ekran.auth;

/**
 * Allowlist for post-login continuation URLs: only same-site absolute paths
 * ("/movies/238", "/list?movie=348"), never protocol-relative or absolute URLs.
 */
public final class NextUrl {

    private static final int MAX_LENGTH = 512;

    private NextUrl() {
    }

    public static String safe(String next) {
        if (next == null || next.isEmpty()) {
            return "/";
        }
        if (next.length() > MAX_LENGTH) {
            return "/";
        }
        if (!next.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")) {
            return "/";
        }
        for (int i = 0; i < next.length(); i++) {
            var c = next.charAt(i);
            if (c <= 0x20 || c == 0x7f) {
                return "/";
            }
        }
        return next;
    }
}
