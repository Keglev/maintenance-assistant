package com.keglevich.maintenanceassistant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * A {@link DataSource} for the {@code test} profile that refuses to connect.
 *
 * <p>The web and OpenAPI tests are about the HTTP and security layers and deliberately run without
 * a database — CI has no Postgres, and the OpenAPI spec has to be exportable on a bare runner.
 * Since Phase 2 the application context contains ingestion beans that need a {@code JdbcClient},
 * so "no DataSource at all" stopped being an option: the context no longer starts.
 *
 * <p>This supplies one that satisfies wiring and throws on first use. That is the honest shape of
 * the arrangement — those tests must never reach the database, and a stub that fails loudly proves
 * it rather than a stub that silently returns nothing. Anything that genuinely needs a database
 * runs under the {@code it} profile against Testcontainers.
 */
@Configuration
@Profile("test")
class NoDatabaseTestConfig {

    @Bean
    @ConditionalOnMissingBean(DataSource.class)
    DataSource unusableDataSource() {
        return new UnusableDataSource();
    }

    private static final class UnusableDataSource implements DataSource {

        private static final String MESSAGE =
                "The 'test' profile has no database on purpose. A test that needs one belongs in "
                        + "the 'it' profile, which runs against a real pgvector container.";

        @Override
        public Connection getConnection() {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException(MESSAGE);
        }

        @Override
        public PrintWriter getLogWriter() {
            return new PrintWriter(System.out);
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            // nothing to configure on a data source that never connects
        }

        @Override
        public void setLoginTimeout(int seconds) {
            // as above
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException(MESSAGE);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
