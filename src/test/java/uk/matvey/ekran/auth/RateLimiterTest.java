package uk.matvey.ekran.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    void allowsUpToTheLimitPerWindow() {
        var limiter = new RateLimiter(2, Duration.ofMillis(50));

        assertThat(limiter.allow("a@b.co")).isTrue();
        assertThat(limiter.allow("a@b.co")).isTrue();
        assertThat(limiter.allow("a@b.co")).isFalse();
        // other keys are independent
        assertThat(limiter.allow("c@d.co")).isTrue();
    }

    @Test
    void resetsAfterTheWindow() throws InterruptedException {
        var limiter = new RateLimiter(1, Duration.ofMillis(30));

        assertThat(limiter.allow("a@b.co")).isTrue();
        assertThat(limiter.allow("a@b.co")).isFalse();
        Thread.sleep(60);
        assertThat(limiter.allow("a@b.co")).isTrue();
    }
}