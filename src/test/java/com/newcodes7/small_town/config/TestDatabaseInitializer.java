package com.newcodes7.small_town.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

public class TestDatabaseInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private static final Pattern JDBC_URL_PATTERN =
            Pattern.compile("^jdbc:postgresql://([^/:?#]+)(?::(\\d+))?/([^?]+).*$");

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        Environment env = applicationContext.getEnvironment();

        String jdbcUrl = env.getProperty("spring.datasource.url");
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
            return;
        }

        String username = env.getProperty("spring.datasource.username");
        String password = env.getProperty("spring.datasource.password");
        String adminDb = env.getProperty("spring.datasource.admin-db", "postgres");

        Matcher matcher = JDBC_URL_PATTERN.matcher(jdbcUrl);
        if (!matcher.matches()) {
            return;
        }

        String host = matcher.group(1);
        String port = matcher.group(2) != null ? matcher.group(2) : "5432";
        String database = matcher.group(3);

        String adminJdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + adminDb;

        try (Connection connection = DriverManager.getConnection(adminJdbcUrl, username, password)) {
            if (!databaseExists(connection, database)) {
                createDatabase(connection, database);
            }
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to ensure test database exists. " +
                    "Check PostgreSQL connectivity and CREATEDB privileges for user '" + username + "'.",
                    ex);
        }

        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            ensureExtension(connection, "vector");
            ensureExtension(connection, "pg_search");
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to ensure required PostgreSQL extensions for tests. " +
                    "Install extensions 'vector' and 'pg_search' in the PostgreSQL instance.",
                    ex);
        }
    }

    private boolean databaseExists(Connection connection, String database) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "select 1 from pg_database where datname = ?")) {
            ps.setString(1, database);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void createDatabase(Connection connection, String database) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + quoteIdentifier(database));
        }
    }

    private void ensureExtension(Connection connection, String extension) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS " + quoteIdentifier(extension));
        }
    }

    private String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
