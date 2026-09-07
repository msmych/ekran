package uk.matvey.ekran.domain;

public class TmdbAuthException extends RuntimeException {

    public TmdbAuthException(String message) {
        super(message);
    }
}