package uk.matvey.ekran.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NextUrlTest {

    @Test
    void allowsSameSitePaths() {
        assertThat(NextUrl.safe("/movies/238")).isEqualTo("/movies/238");
        assertThat(NextUrl.safe("/list?movie=348&name=Sci-fi%20night")).isEqualTo("/list?movie=348&name=Sci-fi%20night");
        assertThat(NextUrl.safe("/account")).isEqualTo("/account");
        assertThat(NextUrl.safe("/")).isEqualTo("/");
    }

    @Test
    void rejectsOpenRedirects() {
        assertThat(NextUrl.safe("https://evil.com")).isEqualTo("/");
        assertThat(NextUrl.safe("//evil.com")).isEqualTo("/");
        assertThat(NextUrl.safe("/\\evil.com")).isEqualTo("/");
        assertThat(NextUrl.safe("https:/evil.com")).isEqualTo("/");
        assertThat(NextUrl.safe("javascript:alert(1)")).isEqualTo("/");
        assertThat(NextUrl.safe("evil.com")).isEqualTo("/");
        assertThat(NextUrl.safe("/%5cevil.com")).isNotEqualTo("//evil.com");
    }

    @Test
    void rejectsSuspiciousInput() {
        assertThat(NextUrl.safe(null)).isEqualTo("/");
        assertThat(NextUrl.safe("")).isEqualTo("/");
        assertThat(NextUrl.safe("/movies\nInjected: x")).isEqualTo("/");
        assertThat(NextUrl.safe("/a b")).isEqualTo("/");
        assertThat(NextUrl.safe("/" + "a".repeat(600))).isEqualTo("/");
    }
}
