package com.securechat.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * JVM-wide singleton containers (started lazily, reused by every test class in the module, stopped
 * by Testcontainers' Ryuk at JVM exit). Same images as docker-compose.yml.
 */
public final class Infra {
    public static final String INTERNAL_TOKEN = "test-internal-token-0123456789abcdef";
    public static final String REDIS_PASSWORD = "test-redis-password";
    private static PostgreSQLContainer postgres;
    private static KafkaContainer kafka;
    private static CassandraContainer cassandra;
    private static GenericContainer<?> redis;
    private static Path jwtKey;

    private Infra() {
    }

    public static synchronized PostgreSQLContainer postgres() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
            postgres.start();
        }
        return postgres;
    }

    public static synchronized KafkaContainer kafka() {
        if (kafka == null) {
            kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"))
                    .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "true")
                    .withEnv("KAFKA_NUM_PARTITIONS", "3");
            kafka.start();
        }
        return kafka;
    }

    public static synchronized CassandraContainer cassandra() {
        if (cassandra == null) {
            cassandra = new CassandraContainer(DockerImageName.parse("cassandra:5.0.9"))
                    .withEnv("MAX_HEAP_SIZE", "512M").withEnv("HEAP_NEWSIZE", "100M")
                    .withStartupTimeout(Duration.ofMinutes(4));
            cassandra.start();
        }
        return cassandra;
    }

    public static synchronized GenericContainer<?> redis() {
        if (redis == null) {
            redis = new GenericContainer<>(DockerImageName.parse("redis:8.10.1-alpine")).withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", REDIS_PASSWORD).waitingFor(Wait.forListeningPort());
            redis.start();
        }
        return redis;
    }

    /** Creates (once) and returns a dedicated database, mirroring one-database-per-service. */
    public static synchronized String database(String name) {
        PostgreSQLContainer pg = postgres();
        String db = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
            var rs = c.createStatement().executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + db + "'");
            if (!rs.next()) {
                c.createStatement().execute("CREATE DATABASE " + db);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return pg.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1");
    }

    public static void postgresProperties(DynamicPropertyRegistry r, String db) {
        String url = database(db);
        r.add("spring.datasource.url", () -> url);
        r.add("spring.datasource.username", () -> postgres().getUsername());
        r.add("spring.datasource.password", () -> postgres().getPassword());
    }

    public static void kafkaProperties(DynamicPropertyRegistry r) {
        r.add("spring.kafka.bootstrap-servers", () -> kafka().getBootstrapServers());
    }

    public static void redisProperties(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", () -> redis().getHost());
        r.add("spring.data.redis.port", () -> redis().getMappedPort(6379));
        r.add("spring.data.redis.username", () -> "default");
        r.add("spring.data.redis.password", () -> REDIS_PASSWORD);
    }

    /** Creates the keyspace (RF 1) once; the service's own CQL migrations create its tables. */
    public static synchronized void cassandraProperties(DynamicPropertyRegistry r, String keyspace) {
        CassandraContainer c = cassandra();
        try (var session = com.datastax.oss.driver.api.core.CqlSession.builder().addContactPoint(c.getContactPoint())
                .withLocalDatacenter(c.getLocalDatacenter()).build()) {
            session.execute("CREATE KEYSPACE IF NOT EXISTS " + keyspace
                    + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}");
        }
        r.add("spring.cassandra.contact-points", () -> c.getHost() + ":" + c.getMappedPort(9042));
        r.add("spring.cassandra.local-datacenter", c::getLocalDatacenter);
        r.add("spring.cassandra.keyspace-name", () -> keyspace);
        r.add("spring.cassandra.username", () -> "cassandra");
        r.add("spring.cassandra.password", () -> "cassandra");
    }

    /** Failure injection: freeze / resume a container's processes (docker pause). */
    public static void pause(GenericContainer<?> c) {
        c.getDockerClient().pauseContainerCmd(c.getContainerId()).exec();
    }

    public static void unpause(GenericContainer<?> c) {
        c.getDockerClient().unpauseContainerCmd(c.getContainerId()).exec();
    }

    public static void commonProperties(DynamicPropertyRegistry r) {
        r.add("securechat.security.internal-token", () -> INTERNAL_TOKEN);
        r.add("management.server.port", () -> "0");
        r.add("logging.structured.format.console", () -> "");
    }

    /** A throwaway RSA PKCS#8 PEM for signing test tokens. */
    public static synchronized Path jwtKeyFile() {
        if (jwtKey == null) {
            try {
                KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
                g.initialize(2048);
                byte[] der = g.generateKeyPair().getPrivate().getEncoded();
                String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                        + "\n-----END PRIVATE KEY-----\n";
                jwtKey = Files.createTempFile("securechat-test-jwt", ".pem");
                Files.writeString(jwtKey, pem);
                jwtKey.toFile().deleteOnExit();
            } catch (IOException | NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        return jwtKey;
    }
}
