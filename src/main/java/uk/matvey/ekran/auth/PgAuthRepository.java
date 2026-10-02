package uk.matvey.ekran.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import javax.sql.DataSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import uk.matvey.ekran.db.Jdbc;

public class PgAuthRepository implements AuthRepository {

    private static final String UNIQUE_VIOLATION = "23505";

    private final DataSource dataSource;

    public PgAuthRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<Long> findUserIdByEmail(String email) {
        return Jdbc.queryOne(dataSource, "look up user", rs -> rs.getLong(1),
            "SELECT id FROM users WHERE email = ?", email);
    }

    // not routed through Jdbc: the unique-violation race with a concurrent
    // first sign-in is handled by reading the winning row back
    @Override
    public long insertUser(String email) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO users (email) VALUES (?) RETURNING id")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            if (isUniqueViolation(e)) {
                // concurrent first sign-in with the same email: the row exists now
                return findUserIdByEmail(email).orElseThrow(() -> new IllegalStateException("User vanished after insert race"));
            }
            throw new IllegalStateException("Cannot create user: " + e.getMessage(), e);
        }
    }

    @Override
    public void insertLoginToken(long userId, String tokenHash, Instant expiresAt, Instant now) {
        Jdbc.update(dataSource, "create login token",
            "INSERT INTO login_tokens (user_id, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?)",
            userId, tokenHash, ts(expiresAt), ts(now));
    }

    // consume-and-read is one transaction: the UPDATE claims the single-use
    // token, the SELECT reads who it belonged to — an already-used or expired
    // token leaves nothing committed
    @Override
    public Optional<Long> consumeLoginToken(String tokenHash, Instant now) {
        return Jdbc.inTransaction(dataSource, "consume login token", conn -> {
            var claimed = Jdbc.update(conn,
                "UPDATE login_tokens SET used_at = ? WHERE token_hash = ? AND used_at IS NULL AND expires_at > ?",
                ts(now), tokenHash, ts(now));
            if (claimed != 1) {
                return Optional.<Long>empty();
            }
            return Jdbc.queryOne(conn, rs -> rs.getLong(1),
                "SELECT user_id FROM login_tokens WHERE token_hash = ?", tokenHash);
        });
    }

    @Override
    public void deleteExpiredLoginTokens(Instant now) {
        Jdbc.update(dataSource, "clean up login tokens",
            "DELETE FROM login_tokens WHERE expires_at < ?", ts(now));
    }

    @Override
    public void insertSession(long userId, String sessionIdHash, Instant expiresAt, Instant now) {
        // opportunistic cleanup rides along on the same connection
        Jdbc.read(dataSource, "create session", conn -> {
            Jdbc.update(conn, "DELETE FROM sessions WHERE expires_at < ?", ts(now));
            Jdbc.update(conn, """
                INSERT INTO sessions (id, user_id, expires_at, created_at) VALUES (?, ?, ?, ?)""",
                sessionIdHash, userId, ts(expiresAt), ts(now));
            return null;
        });
    }

    @Override
    public Optional<AuthRepository.SessionUser> findSession(String sessionIdHash, Instant now) {
        return Jdbc.queryOne(dataSource, "look up session",
            rs -> new AuthRepository.SessionUser(rs.getLong(1), rs.getString(2)), """
                SELECT u.id, u.email FROM sessions s
                JOIN users u ON u.id = s.user_id
                WHERE s.id = ? AND s.expires_at > ?""", sessionIdHash, ts(now));
    }

    @Override
    public boolean deleteSession(String sessionIdHash) {
        return Jdbc.update(dataSource, "delete session",
            "DELETE FROM sessions WHERE id = ?", sessionIdHash) > 0;
    }

    private static OffsetDateTime ts(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static boolean isUniqueViolation(SQLException e) {
        if (e instanceof PSQLException psql) {
            ServerErrorMessage message = psql.getServerErrorMessage();
            return message != null && UNIQUE_VIOLATION.equals(message.getSQLState());
        }
        return false;
    }
}
