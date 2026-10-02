package uk.matvey.ekran.email;

/** Delivery failed — the caller shows a generic retry message; details stay in server logs. */
public class EmailException extends RuntimeException {

    public EmailException(String message) {
        super(message);
    }

    public EmailException(String message, Throwable cause) {
        super(message, cause);
    }
}
