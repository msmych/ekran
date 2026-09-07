package uk.matvey.ekran.domain;

public class TmdbUnavailableException extends RuntimeException {

    public TmdbUnavailableException(String message) {
        super(message);
    }
}