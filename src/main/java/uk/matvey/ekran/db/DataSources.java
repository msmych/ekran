package uk.matvey.ekran.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import uk.matvey.ekran.config.AppConfig;

public final class DataSources {

    private DataSources() {
    }

    public static HikariDataSource create(AppConfig config) {
        var db = config.dbConnection();
        var hikari = new HikariConfig();
        hikari.setJdbcUrl(db.jdbcUrl());
        hikari.setUsername(db.user());
        hikari.setPassword(db.password());
        hikari.setPoolName("ekran-db");
        hikari.setMaximumPoolSize(8);
        hikari.setConnectionTimeout(5_000);
        // fail fast at startup if the database is unreachable
        hikari.setInitializationFailTimeout(5_000);
        return new HikariDataSource(hikari);
    }
}
