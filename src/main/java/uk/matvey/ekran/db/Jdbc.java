package uk.matvey.ekran.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * The thin JDBC layer every repository shares: bind params, map rows, run
 * transactions — and wrap {@link SQLException} into an IllegalStateException
 * carrying the operation's name. All primitives are parameterized (no
 * string concatenation of values ever reaches the driver).
 */
public final class Jdbc {

    private Jdbc() {
    }

    /** maps one result row */
    @FunctionalInterface
    public interface Row<T> {
        T read(ResultSet rs) throws SQLException;
    }

    /** a unit of work against one connection (one transaction when used via {@link #inTransaction}) */
    @FunctionalInterface
    public interface Work<T> {
        T apply(Connection conn) throws SQLException;
    }

    // -- connection-level primitives (errors are named by the calling scope) --

    public static <T> List<T> queryList(Connection conn, Row<T> row, String sql, Object... params)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                var results = new ArrayList<T>();
                while (rs.next()) {
                    results.add(row.read(rs));
                }
                return results;
            }
        }
    }

    public static <T> Optional<T> queryOne(Connection conn, Row<T> row, String sql, Object... params)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                // ofNullable: a row mapper may read a NULL column (Optional.empty() then means "no value")
                return rs.next() ? Optional.ofNullable(row.read(rs)) : Optional.empty();
            }
        }
    }

    /** @return the number of affected rows */
    public static int update(Connection conn, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    // -- data-source-level: acquire a connection, name the error --

    public static <T> List<T> queryList(DataSource ds, String err, Row<T> row, String sql, Object... params) {
        try (Connection conn = ds.getConnection()) {
            return queryList(conn, row, sql, params);
        } catch (SQLException e) {
            throw failure(err, e);
        }
    }

    public static <T> Optional<T> queryOne(DataSource ds, String err, Row<T> row, String sql, Object... params) {
        try (Connection conn = ds.getConnection()) {
            return queryOne(conn, row, sql, params);
        } catch (SQLException e) {
            throw failure(err, e);
        }
    }

    /** @return the number of affected rows */
    public static int update(DataSource ds, String err, String sql, Object... params) {
        try (Connection conn = ds.getConnection()) {
            return update(conn, sql, params);
        } catch (SQLException e) {
            throw failure(err, e);
        }
    }

    /** several statements on one connection, autocommit per statement */
    public static <T> T read(DataSource ds, String err, Work<T> work) {
        try (Connection conn = ds.getConnection()) {
            return work.apply(conn);
        } catch (SQLException e) {
            throw failure(err, e);
        }
    }

    /**
     * all-or-nothing: the work commits together or not at all. Any failure —
     * SQL or runtime (e.g. a NotFoundException thrown by an ownership check
     * mid-transaction) — rolls back before propagating.
     */
    public static <T> T inTransaction(DataSource ds, String err, Work<T> work) {
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            try {
                var result = work.apply(conn);
                conn.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw failure(err, e);
            }
        } catch (SQLException e) {
            throw failure(err, e);
        }
    }

    /** one batched statement over many parameter sets, in one transaction */
    public static void batch(DataSource ds, String err, String sql, List<Object[]> paramRows) {
        if (paramRows.isEmpty()) {
            return;
        }
        inTransaction(ds, err, conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (var params : paramRows) {
                    bind(ps, params);
                    ps.addBatch();
                }
                ps.executeBatch();
                return null;
            }
        });
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (var i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    private static RuntimeException failure(String err, Exception e) {
        if (e instanceof SQLException sql) {
            return new IllegalStateException("Cannot " + err + ": " + sql.getMessage(), sql);
        }
        return (RuntimeException) e;
    }
}
