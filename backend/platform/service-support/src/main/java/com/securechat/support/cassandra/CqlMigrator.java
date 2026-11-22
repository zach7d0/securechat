package com.securechat.support.cassandra;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Minimal versioned CQL migrations owned by each Cassandra-backed service:
 * {@code classpath:cql/V<n>__<name>.cql}, statements separated by ';', applied in order and recorded
 * in {@code schema_migrations} of the service's own keyspace. Statements must be idempotent
 * ({@code IF NOT EXISTS}) because Cassandra DDL is not transactional.
 */
public final class CqlMigrator {
    private static final Logger log = LoggerFactory.getLogger(CqlMigrator.class);
    private static final Pattern NAME = Pattern.compile("V(\\d+)__([\\w-]+)\\.cql");

    private CqlMigrator() {
    }

    public static void migrate(CqlSession session) {
        exec(session, "CREATE TABLE IF NOT EXISTS schema_migrations (version int PRIMARY KEY, description text, applied_at timestamp)");
        Resource[] files;
        try {
            files = new PathMatchingResourcePatternResolver().getResources("classpath:cql/V*__*.cql");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Resource> sorted = Arrays.stream(files).filter(r -> NAME.matcher(r.getFilename()).matches())
                .sorted(Comparator.comparingInt(r -> version(r.getFilename()))).toList();
        for (Resource r : sorted) {
            int v = version(r.getFilename());
            boolean applied = session.execute(SimpleStatement.newInstance("SELECT version FROM schema_migrations WHERE version = ?", v)
                    .setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM)).one() != null;
            if (applied) {
                continue;
            }
            String cql;
            try {
                cql = r.getContentAsString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            for (String stmt : cql.replaceAll("(?m)^\\s*--.*$", "").split(";")) {
                if (!stmt.isBlank()) {
                    exec(session, stmt.strip());
                }
            }
            Matcher m = NAME.matcher(r.getFilename());
            m.matches();
            session.execute(SimpleStatement.newInstance("INSERT INTO schema_migrations (version, description, applied_at) VALUES (?, ?, ?)",
                    v, m.group(2), Instant.now()).setConsistencyLevel(ConsistencyLevel.LOCAL_QUORUM));
            log.info("applied CQL migration V{}", v);
        }
    }

    private static void exec(CqlSession session, String cql) {
        session.execute(SimpleStatement.newInstance(cql).setTimeout(Duration.ofSeconds(60)));
    }

    private static int version(String name) {
        Matcher m = NAME.matcher(name);
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
    }
}
