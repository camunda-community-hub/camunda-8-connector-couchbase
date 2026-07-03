package io.camunda.connector.couchbase;

import com.couchbase.client.core.error.AmbiguousTimeoutException;
import com.couchbase.client.core.error.AuthenticationFailureException;
import com.couchbase.client.core.error.UnambiguousTimeoutException;
import com.couchbase.client.core.service.ServiceType;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.diagnostics.WaitUntilReadyOptions;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.camunda.connector.api.error.ConnectorException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caches Cluster instances with a bounded Caffeine cache.
 *
 * Cache key includes a SHA-256 fingerprint of the password so that credential rotation
 * naturally creates a new entry rather than reusing a stale connection. A secondary index
 * (USER_KEY_INDEX) maps connectionString|username → current cache key so that stale entries
 * from a previous password are proactively evicted when new credentials are first used —
 * this closes the passive-rotation window where an old authenticated cluster could otherwise
 * linger until the TTL expires. Evicted clusters are disconnected immediately via the removal
 * listener. All clusters are disconnected cleanly on JVM shutdown via a registered shutdown hook.
 *
 * Cache size and TTL can be tuned via system properties:
 *   -Dcouchbase.connector.cacheMaxSize=50     (default: 50)
 *   -Dcouchbase.connector.cacheTtlMinutes=30  (default: 30)
 */
public class CouchbaseClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(CouchbaseClientFactory.class);
    private static final Duration WAIT_UNTIL_READY_TIMEOUT = Duration.ofSeconds(15);

    private static final int      CACHE_MAX_SIZE   = Integer.getInteger("couchbase.connector.cacheMaxSize", 50);
    private static final long     CACHE_TTL_MINUTES = Long.getLong("couchbase.connector.cacheTtlMinutes", 30);

    private static final Cache<String, Cluster> CLUSTER_CACHE = Caffeine.newBuilder()
        .maximumSize(CACHE_MAX_SIZE)
        .expireAfterAccess(Duration.ofMinutes(CACHE_TTL_MINUTES))
        .removalListener((String key, Cluster cluster, RemovalCause cause) -> {
            if (cluster != null) {
                LOGGER.info("Evicting Couchbase cluster connection (cause={})", cause);
                try { cluster.disconnect(); } catch (Exception ignored) {}
            }
        })
        .build();

    /** Secondary index: "connectionString|username" → active cache key (for proactive stale-entry eviction). */
    private static final ConcurrentMap<String, String> USER_KEY_INDEX = new ConcurrentHashMap<>();

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
        String userKey  = connectionString + "|" + username;
        String cacheKey = userKey + "|" + sha256(password);
        try {
            Cluster cluster = CLUSTER_CACHE.get(cacheKey, key -> {
                LOGGER.info("Creating new Couchbase Cluster connection to {}", connectionString);
                Cluster c = Cluster.connect(connectionString, username, password);
                // Only wait for KV (data) service — Query/Index readiness is verified lazily
                // when those operations are actually invoked. This avoids timeout failures when
                // Query is on a remapped port (e.g. Docker) or not yet fully initialised.
                c.waitUntilReady(WAIT_UNTIL_READY_TIMEOUT,
                    WaitUntilReadyOptions.waitUntilReadyOptions()
                        .serviceTypes(ServiceType.KV));
                LOGGER.info("Couchbase Cluster KV ready at {}. N1QL/Query service readiness is deferred "
                    + "— first query may have higher latency if the Query service is still warming up.", connectionString);
                return c;
            });

            // Proactively evict any stale entry for the same user that was created with a different password.
            // This closes the passive credential-rotation window without waiting for the TTL to expire.
            String prevKey = USER_KEY_INDEX.put(userKey, cacheKey);
            if (prevKey != null && !prevKey.equals(cacheKey)) {
                LOGGER.info("Credential change detected for {}@{} — evicting stale cluster entry", username, connectionString);
                CLUSTER_CACHE.invalidate(prevKey);
            }

            return cluster;
        } catch (AuthenticationFailureException e) {
            CLUSTER_CACHE.invalidate(cacheKey);
            throw new ConnectorException("AUTHENTICATION_FAILED",
                "Authentication failed. Verify the Couchbase username and password.", e);
        } catch (UnambiguousTimeoutException | AmbiguousTimeoutException e) {
            CLUSTER_CACHE.invalidate(cacheKey);
            if (hasUnknownHostCause(e)) {
                throw new ConnectorException("UNKNOWN_HOST",
                    "Hostname in the connection string could not be resolved. Verify the connection string.", e);
            }
            throw new ConnectorException("CONNECTION_TIMEOUT",
                "Connection to Couchbase timed out. Verify the connection string and network accessibility.", e);
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            CLUSTER_CACHE.invalidate(cacheKey);
            LOGGER.error("Failed to connect to Couchbase: {}", e.getMessage(), e);
            if (hasUnknownHostCause(e)) {
                throw new ConnectorException("UNKNOWN_HOST",
                    "Hostname in the connection string could not be resolved. Verify the connection string.", e);
            }
            throw new ConnectorException("CONNECTION_FAILED",
                "Failed to connect to Couchbase. Check the connection string, credentials, and network.", e);
        }
    }

    private static boolean hasUnknownHostCause(Throwable t) {
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof UnknownHostException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /** Force-evict a specific credential set, e.g. after detecting an auth failure. */
    public static void evict(String connectionString, String username, String password) {
        String userKey  = connectionString + "|" + username;
        String cacheKey = userKey + "|" + sha256(password);
        CLUSTER_CACHE.invalidate(cacheKey);
        USER_KEY_INDEX.remove(userKey, cacheKey);
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
