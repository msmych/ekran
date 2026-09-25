package uk.matvey.ekran.auth;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TokensTest {

    @Test
    void randomTokenIs43UrlSafeCharsOf256Bits() {
        for (var i = 0; i < 100; i++) {
            var token = Tokens.randomToken();
            assertThat(token).hasSize(43);
            assertThat(token).matches("[A-Za-z0-9_-]{43}");
        }
    }

    @Test
    void randomTokensAreUnpredictable() {
        Set<String> tokens = new HashSet<>();
        for (var i = 0; i < 1_000; i++) {
            tokens.add(Tokens.randomToken());
        }
        assertThat(tokens).hasSize(1_000);
    }

    @Test
    void sha256IsStableAndKnown() {
        assertThat(Tokens.sha256("abc")).isEqualTo("ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0");
        assertThat(Tokens.sha256("abc")).isEqualTo(Tokens.sha256("abc"));
        assertThat(Tokens.sha256("abd")).isNotEqualTo(Tokens.sha256("abc"));
    }

    @Test
    void sha256DiffersFromRawValue() {
        var token = Tokens.randomToken();
        assertThat(Tokens.sha256(token)).isNotEqualTo(token);
    }
}