package uk.matvey.ekran.auth;

import java.util.regex.Pattern;

public final class Emails {

    // pragmatic validation: Resend rejects malformed addresses at delivery time anyway
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private Emails() {
    }

    public static boolean isValid(String email) {
        return email != null && email.length() <= 254 && EMAIL.matcher(email).matches();
    }

    public static String normalize(String email) {
        return email.trim().toLowerCase();
    }
}
