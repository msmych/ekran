package uk.matvey.ekran.domain;

/** A movie id with an optional personal note — a mark or a playlist membership. */
public record MovieNote(long movieId, String note) {

    public MovieNote {
        note = normalize(note);
    }

    /** Trims a note: null/blank → null (no note). */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        var note = raw.strip();
        return note.isEmpty() ? null : note;
    }
}
