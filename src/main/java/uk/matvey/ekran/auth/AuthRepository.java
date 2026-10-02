package uk.matvey.ekran.auth;

import java.time.Instant;
import java.util.Optional;

public interface AuthRepository {

    record SessionUser(long userId, String email) {
    }

    Optional<Long> findUserIdByEmail(String email);

    /** Creates the user; on a concurrent-insert race for the same email, returns the existing id. */
    long insertUser(String email);

    void insertLoginToken(long userId, String tokenHash, Instant expiresAt, Instant now);

    /** Atomically marks a valid unused, unexpired token as used; empty if invalid/expired/already used. */
    Optional<Long> consumeLoginToken(String tokenHash, Instant now);

    void deleteExpiredLoginTokens(Instant now);

    /** Inserts the session, opportunistically deleting expired sessions on the same connection. */
    void insertSession(long userId, String sessionIdHash, Instant expiresAt, Instant now);

    Optional<SessionUser> findSession(String sessionIdHash, Instant now);

    boolean deleteSession(String sessionIdHash);
}
