package uk.matvey.ekran.auth;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory AuthRepository for service/route tests — no database needed. */
public class InMemoryAuthRepository implements AuthRepository {

    public record LoginToken(long userId, String tokenHash, Instant expiresAt, Instant usedAt) {
    }

    public record Session(long userId, Instant expiresAt) {
    }

    private final Map<String, Long> userIdByEmail = new ConcurrentHashMap<>();
    private final Map<Long, String> emailByUserId = new ConcurrentHashMap<>();
    private final List<LoginToken> loginTokens = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    @Override
    public Optional<Long> findUserIdByEmail(String email) {
        return Optional.ofNullable(userIdByEmail.get(email));
    }

    @Override
    public long insertUser(String email) {
        var id = ids.incrementAndGet();
        var existing = userIdByEmail.putIfAbsent(email, id);
        if (existing != null) {
            return existing;
        }
        emailByUserId.put(id, email);
        return id;
    }

    @Override
    public void insertLoginToken(long userId, String tokenHash, Instant expiresAt, Instant now) {
        loginTokens.add(new LoginToken(userId, tokenHash, expiresAt, null));
    }

    @Override
    public Optional<Long> consumeLoginToken(String tokenHash, Instant now) {
        synchronized (loginTokens) {
            for (var i = 0; i < loginTokens.size(); i++) {
                var token = loginTokens.get(i);
                if (token.tokenHash().equals(tokenHash) && token.usedAt() == null && token.expiresAt().isAfter(now)) {
                    loginTokens.set(i, new LoginToken(token.userId(), token.tokenHash(), token.expiresAt(), now));
                    return Optional.of(token.userId());
                }
            }
            return Optional.empty();
        }
    }

    @Override
    public void deleteExpiredLoginTokens(Instant now) {
        loginTokens.removeIf(token -> token.expiresAt().isBefore(now));
    }

    @Override
    public void insertSession(long userId, String sessionIdHash, Instant expiresAt, Instant now) {
        sessions.values().removeIf(session -> session.expiresAt().isBefore(now));
        sessions.put(sessionIdHash, new Session(userId, expiresAt));
    }

    @Override
    public Optional<AuthRepository.SessionUser> findSession(String sessionIdHash, Instant now) {
        var session = sessions.get(sessionIdHash);
        if (session == null || !session.expiresAt().isAfter(now)) {
            return Optional.empty();
        }
        var email = emailByUserId.get(session.userId());
        return email == null ? Optional.empty() : Optional.of(new AuthRepository.SessionUser(session.userId(), email));
    }

    @Override
    public boolean deleteSession(String sessionIdHash) {
        return sessions.remove(sessionIdHash) != null;
    }

    public List<LoginToken> loginTokens() {
        synchronized (loginTokens) {
            return List.copyOf(loginTokens);
        }
    }

    public boolean hasSessionForRaw(String rawSessionId) {
        return sessions.containsKey(Tokens.sha256(rawSessionId));
    }
}