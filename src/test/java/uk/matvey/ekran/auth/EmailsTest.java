package uk.matvey.ekran.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailsTest {

    @Test
    void acceptsOrdinaryAddresses() {
        assertThat(Emails.isValid("foo@bar.com")).isTrue();
        assertThat(Emails.isValid("A.B+c@d-e.co.uk")).isTrue();
        assertThat(Emails.isValid("first.last@sub.domain.io")).isTrue();
    }

    @Test
    void rejectsMalformedAddresses() {
        assertThat(Emails.isValid(null)).isFalse();
        assertThat(Emails.isValid("")).isFalse();
        assertThat(Emails.isValid("foo")).isFalse();
        assertThat(Emails.isValid("foo@bar")).isFalse();
        assertThat(Emails.isValid("foo bar@baz.com")).isFalse();
        assertThat(Emails.isValid("foo@bar.com.")).isFalse();
        assertThat(Emails.isValid("a".repeat(250) + "@b.co")).isFalse();
    }

    @Test
    void normalizesTrimsAndLowercases() {
        assertThat(Emails.normalize("  Foo@BAR.com ")).isEqualTo("foo@bar.com");
    }
}
