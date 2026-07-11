package com.newcodes7.small_town.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

public class TestDatabaseInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    private static final Pattern JDBC_URL_PATTERN =
            Pattern.compile("^jdbc:postgresql://([^/:?#]+)(?::(\\d+))?/([^?]+).*$");

    private static final String WORKER_PROPERTY_SOURCE = "testWorkerDatasource";

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        ConfigurableEnvironment env = applicationContext.getEnvironment();
        applyWorkerDatabaseName(env);

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

    /**
     * Gradle 병렬 포크 실행 시 포크마다 다른 DB(small_town_test_w{slot})를 쓰도록 URL을 재작성한다.
     * create-drop 스키마가 포크 간 충돌하지 않게 하기 위함. IDE 단독 실행(worker 프로퍼티 없음)이나
     * 단일 포크(slots<=1)에서는 기존 DB 이름을 그대로 쓴다.
     */
    private void applyWorkerDatabaseName(ConfigurableEnvironment env) {
        if (env.getPropertySources().contains(WORKER_PROPERTY_SOURCE)) {
            return; // spring.factories + initializer.classes 이중 등록으로 인한 재실행 방지
        }
        String worker = System.getProperty("org.gradle.test.worker");
        int slots = Integer.getInteger("test.db.slots", 1);
        if (worker == null || slots <= 1) {
            return;
        }
        String url = env.getProperty("spring.datasource.url");
        if (url == null || !url.startsWith("jdbc:postgresql://")) {
            return;
        }
        long slot;
        try {
            // worker id를 그대로 사용한다. modulo(id % slots)는 Gradle 데몬이 오래 살아
            // worker id가 1,2가 아닌 3,5처럼 커지면 두 포크가 같은 DB에 배정될 수 있고,
            // 한 포크의 create-drop DDL과 다른 포크의 트랜잭션이 락 교착을 일으킨다.
            slot = Long.parseLong(worker.trim());
        } catch (NumberFormatException ex) {
            return;
        }
        String newUrl = url.replaceFirst("(/[^/?]+)(\\?[^#]*)?$", "$1_w" + slot + "$2");
        env.getPropertySources().addFirst(
                new MapPropertySource(WORKER_PROPERTY_SOURCE,
                        Map.of("spring.datasource.url", newUrl)));
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
