package uk.matvey.ekran.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    @Test
    void allowsUpToTheLimitPerWindow() {
        var clock = new MutableClock();
        var limiter = new RateLimiter(2, Duration.ofMillis(50), clock);

        assertThat(limiter.allow("a@b.co")).isTrue();
        assertThat(limiter.allow("a@b.co")).isTrue();
        assertThat(limiter.allow("a@b.co")).isFalse();
        // other keys are independent
        assertThat(limiter.allow("c@d.co")).isTrue();

        // the window resets when it expires — no wall clock involved
        clock.advanceMillis(50);
        assertThat(limiter.allow("a@b.co")).isTrue();
    }

    @Test
    void resetsAfterTheWindow() {
        var clock = new MutableClock();
        var limiter = new RateLimiter(1, Duration.ofMillis(30), clock);

        assertThat(limiter.allow("a@b.co")).isTrue();
        assertThat(limiter.allow("a@b.co")).isFalse();
        clock.advanceMillis(29);
        assertThat(limiter.allow("a@b.co")).isFalse();
        clock.advanceMillis(1);
        assertThat(limiter.allow("a@b.co")).isTrue();
    }

    /** A clock the test can advance, so window boundaries need no sleeps. */
    private static final class MutableClock extends Clock {
        private Instant instant = Instant.EPOCH;

        void advanceMillis(long millis) {
            instant = instant.plusMillis(millis);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
