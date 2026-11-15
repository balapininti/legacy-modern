package db.migration;

import com.modtoolkit.core.VectorSupport;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * pgvector and {@code chunk.embedding} only where the server has the extension; elsewhere search is lexical-only and
 * doctor warns. A Java migration because SQL cannot make CREATE EXTENSION conditional on the extension's files.
 * Flyway finds it through its default location, {@code classpath:db/migration}, which also scans this package.
 */
public class V5__optional_vector extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        VectorSupport.enable(context.getConnection());
    }
}
