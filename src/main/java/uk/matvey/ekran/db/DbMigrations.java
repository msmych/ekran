package uk.matvey.ekran.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal sequential migration runner: SQL files in {@code /db/migration/V<n>.sql}
 * (1, 2, 3, …) are applied in order, once each, tracked in {@code schema_migrations}.
 * Each migration runs in its own transaction.
 */
public final class DbMigrations {

    private static final Logger log = LoggerFactory.getLogger(DbMigrations.class);

    private DbMigrations() {
    }

    public static void migrate(DataSource dataSource) {
        ensureMigrationsTable(dataSource);
        int applied = 0;
        for (int version = 1; ; version++) {
            var sql = loadMigration(version);
            if (sql == null) {
                // a gap in the sequence would silently skip everything after it —
                // a renumbered or deleted migration must fail loudly instead
                if (migrationExists(version + 1)) {
                    throw new IllegalStateException("Missing migration V" + version + " but V" + (version + 1) + " exists: the sequence has a gap");
                }
                break;
            }
            if (isApplied(dataSource, version)) {
                continue;
            }
            apply(dataSource, version, sql);
            applied++;
        }
        if (applied > 0) {
            log.info("applied {} database migration(s)", applied);
        }
    }

    private static void ensureMigrationsTable(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection(); var st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS schema_migrations (
                    version INTEGER PRIMARY KEY,
                    applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )""");
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot initialize the schema_migrations table: " + e.getMessage(), e);
        }
    }

    private static boolean isApplied(DataSource dataSource, int version) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM schema_migrations WHERE version = ?")) {
            ps.setInt(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read schema_migrations: " + e.getMessage(), e);
        }
    }

    private static void apply(DataSource dataSource, int version, String sql) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (var st = conn.createStatement();
                 var insert = conn.prepareStatement("INSERT INTO schema_migrations (version) VALUES (?)")) {
                st.execute(sql);
                insert.setInt(1, version);
                insert.executeUpdate();
                conn.commit();
                log.info("applied database migration V{}", version);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Migration V" + version + " failed: " + e.getMessage(), e);
        }
    }

    private static String loadMigration(int version) {
        try (var resource = DbMigrations.class.getResourceAsStream("/db/migration/V" + version + ".sql")) {
            if (resource == null) {
                return null;
            }
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read migration V" + version, e);
        }
    }

    private static boolean migrationExists(int version) {
        return DbMigrations.class.getResource("/db/migration/V" + version + ".sql") != null;
    }
}
