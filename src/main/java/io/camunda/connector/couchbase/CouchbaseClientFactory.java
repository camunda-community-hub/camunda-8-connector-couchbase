package io.camunda.connector.couchbase;

import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.diagnostics.WaitUntilReadyOptions;
import com.couchbase.client.core.service.ServiceType;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caches Cluster instances with a bounded Caffeine cache (max 50 entries, 30-minute idle TTL).
 *
 * Cache key includes a SHA-256 fingerprint of the password so that credential rotation
 * naturally creates a new entry rather than reusing a stale connection. Evicted or invalidated
 * clusters are disconnected immediately via the removal listener. All clusters are disconnected
 * cleanly on JVM shutdown via a registered shutdown hook.
 */
public class CouchbaseClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(CouchbaseClientFactory.class);
    private static final Duration WAIT_UNTIL_READY_TIMEOUT = Duration.ofSeconds(15);

    private static final Cache<String, Cluster> CLUSTER_CACHE = Caffeine.newBuilder()
        .maximumSize(50)
        .expireAfterAccess(Duration.ofMinutes(30))
        .removalListener((String key, Cluster cluster, RemovalCause cause) -> {
            if (cluster != null) {
                LOGGER.info("Evicting Couchbase cluster connection (cause={})", cause);
                try { cluster.disconnect(); } catch (Exception ignored) {}
            }
        })
        .build();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutting down all cached Couchbase cluster connections");
            CLUSTER_CACHE.asMap().values().forEach(c -> {
                try { c.disconnect(); } catch (Exception ignored) {}
            });
        }, "couchbase-shutdown-hook"));
    }

    private CouchbaseClientFactory() {}

    public static Cluster getCluster(String connectionString, String username, String password) {
        String cacheKey = connectionString + "|" + username + "|" + sha256(password);
        return CLUSTER_CACHE.get(cacheKey, key -> {
            LOGGER.info("Creating new Couchbase Cluster connection to {}", connectionString);
            Cluster cluster = Cluster.connect(connectionString, username, password);
            // Only wait for KV (data) service — Query/Index readiness is verified lazily
            // when those operations are actually invoked. This avoids timeout failures when
            // Query is on a remapped port (e.g. Docker) or not yet fully initialised.
            cluster.waitUntilReady(WAIT_UNTIL_READY_TIMEOUT,
                WaitUntilReadyOptions.waitUntilReadyOptions()
                    .serviceTypes(ServiceType.KV));
            LOGGER.info("Couchbase Cluster KV ready at {}", connectionString);
            return cluster;
        });
    }

    /** Force-evict a specific credential set, e.g. after detecting an auth failure. */
    public static void evict(String connectionString, String username, String password) {
        CLUSTER_CACHE.invalidate(connectionString + "|" + username + "|" + sha256(password));
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
