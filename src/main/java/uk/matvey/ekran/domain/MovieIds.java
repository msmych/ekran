package uk.matvey.ekran.domain;

import java.util.List;
import java.util.regex.Pattern;

/** TMDB movie ids as used in URLs and forms: positive, at most 10 digits. */
public final class MovieIds {

    public static final Pattern VALID = Pattern.compile("[1-9][0-9]{0,9}");

    /** Bulk movie sets are capped everywhere they render (one TMDB call per movie): lists, playlists, marks. */
    public static final int MAX_SET = 100;

    private MovieIds() {
    }

    public static boolean isValid(String raw) {
        return raw != null && VALID.matcher(raw.trim()).matches();
    }

    /** Filters to well-formed, distinct ids, preserving order. Bulk sources are untrusted. */
    public static List<Long> validOf(List<String> params) {
        return params.stream()
            .filter(MovieIds::isValid)
            .map(raw -> Long.parseLong(raw.trim()))
            .distinct()
            .toList();
    }
}