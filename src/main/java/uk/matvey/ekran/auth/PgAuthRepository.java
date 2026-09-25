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

public class PgAuthRepository implements AuthRepository {

    private static final String UNIQUE_VIOLATION = "23505";

    private final DataSource dataSource;

    public PgAuthRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<Long> findUserIdByEmail(String email) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot look up user: " + e.getMessage(), e);
        }
    }

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
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO login_tokens (user_id, token_hash, expires_at, created_at) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, userId);
            ps.setString(2, tokenHash);
            ps.setObject(3, ts(expiresAt));
            ps.setObject(4, ts(now));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create login token: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Long> consumeLoginToken(String tokenHash, Instant now) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement update = conn.prepareStatement(
                     "UPDATE login_tokens SET used_at = ? WHERE token_hash = ? AND used_at IS NULL AND expires_at > ?");
                 PreparedStatement select = conn.prepareStatement(
                     "SELECT user_id FROM login_tokens WHERE token_hash = ?")) {
                update.setObject(1, ts(now));
                update.setString(2, tokenHash);
                update.setObject(3, ts(now));
                if (update.executeUpdate() != 1) {
                    conn.rollback();
                    return Optional.empty();
                }
                select.setString(1, tokenHash);
                try (ResultSet rs = select.executeQuery()) {
                    rs.next();
                    var userId = rs.getLong(1);
                    conn.commit();
                    return Optional.of(userId);
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot consume login token: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteExpiredLoginTokens(Instant now) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM login_tokens WHERE expires_at < ?")) {
            ps.setObject(1, ts(now));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot clean up login tokens: " + e.getMessage(), e);
        }
    }

    @Override
    public void insertSession(long userId, String sessionIdHash, Instant expiresAt, Instant now) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement delete = conn.prepareStatement("DELETE FROM sessions WHERE expires_at < ?");
             PreparedStatement insert = conn.prepareStatement(
                 "INSERT INTO sessions (id, user_id, expires_at, created_at) VALUES (?, ?, ?, ?)")) {
            delete.setObject(1, ts(now));
            delete.executeUpdate();
            insert.setString(1, sessionIdHash);
            insert.setLong(2, userId);
            insert.setObject(3, ts(expiresAt));
            insert.setObject(4, ts(now));
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create session: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<String> findSessionEmail(String sessionIdHash, Instant now) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                 SELECT u.email FROM sessions s
                 JOIN users u ON u.id = s.user_id
                 WHERE s.id = ? AND s.expires_at > ?""")) {
            ps.setString(1, sessionIdHash);
            ps.setObject(2, ts(now));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot look up session: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean deleteSession(String sessionIdHash) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM sessions WHERE id = ?")) {
            ps.setString(1, sessionIdHash);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot delete session: " + e.getMessage(), e);
        }
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