package com.modtoolkit.core;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * pgvector is optional: a client's own PostgreSQL may not have it, and a first question never needs vectors. Vector
 * search is on exactly when column {@code chunk.embedding} exists. Migration V5 adds it when the server offers the
 * extension; {@code toolkit reindex} adds it later, with the same statements, once the extension has been installed.
 */
public final class VectorSupport {
    private static final Logger log = LoggerFactory.getLogger(VectorSupport.class);

    public static final String HOW_TO_ENABLE = "install pgvector 0.8 for PostgreSQL 17 on the database server, then run `toolkit reindex`";

    private VectorSupport() {}

    /** Whether {@code chunk.embedding} exists, which is what every vector query needs. */
    public static boolean available(JdbcTemplate jdbc) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) VectorSupport::available));
    }

    public static boolean available(Connection con) throws SQLException {
        return exists(con, "SELECT 1 FROM pg_attribute WHERE attrelid = to_regclass('chunk') AND attname = 'embedding' AND NOT attisdropped");
    }

    /** Whether the server can create the extension (its files are installed), whether or not it is created yet. */
    public static boolean installable(Connection con) throws SQLException {
        return exists(con, "SELECT 1 FROM pg_available_extensions WHERE name = 'vector'");
    }

    /** Creates the extension and the column when the server offers pgvector. Returns whether vectors are now available. */
    public static boolean enable(JdbcTemplate jdbc) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) VectorSupport::enable));
    }

    /**
     * What V5 runs. Without the extension's files this does nothing. When creating it fails anyway (the role may not
     * create extensions), that is logged and the database stays lexical-only rather than failing the migration.
     */
    public static boolean enable(Connection con) throws SQLException {
        if (available(con)) return true;
        if (!installable(con)) return false;
        boolean tx = !con.getAutoCommit();
        Savepoint sp = tx ? con.setSavepoint() : null;
        try (Statement st = con.createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS vector");
            st.execute("ALTER TABLE chunk ADD COLUMN IF NOT EXISTS embedding vector");
        } catch (SQLException e) {
            if (sp != null) con.rollback(sp);
            log.warn("pgvector is installed but could not be enabled ({}); search stays lexical-only. Ask the DBA to run CREATE EXTENSION vector, then run toolkit reindex", e.getMessage());
            return false;
        }
        if (sp != null) con.releaseSavepoint(sp);
        return true;
    }

    private static boolean exists(Connection con, String sql) throws SQLException {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next();
        }
    }
}
