package io.camunda.connector.couchbase;

import com.couchbase.client.core.error.AuthenticationFailureException;
import com.couchbase.client.core.error.CollectionNotFoundException;
import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.core.error.ScopeNotFoundException;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.json.JsonArray;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.kv.MutationResult;
import com.couchbase.client.java.query.QueryOptions;
import com.couchbase.client.java.query.QueryResult;
import com.couchbase.client.java.query.QueryScanConsistency;
import io.camunda.connector.api.annotation.Operation;
import io.camunda.connector.api.annotation.OutboundConnector;
import io.camunda.connector.api.annotation.Variable;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.api.outbound.OutboundConnectorProvider;
import io.camunda.connector.couchbase.model.CouchbaseAuthentication;
import io.camunda.connector.couchbase.model.DeleteDocumentRequest;
import io.camunda.connector.couchbase.model.GetDocumentRequest;
import io.camunda.connector.couchbase.model.QueryRequest;
import io.camunda.connector.couchbase.model.ReplaceDocumentRequest;
import io.camunda.connector.couchbase.model.UpsertDocumentRequest;
import io.camunda.connector.generator.java.annotation.ElementTemplate;
import io.camunda.connector.generator.java.annotation.ElementTemplate.PropertyGroup;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@OutboundConnector(
    name = "Couchbase DB Connector",
    type = "io.camunda:couchbase:1",
    inputVariables = {}
)
@ElementTemplate(
    id = "io.camunda.connector.couchbase.v1",
    name = "Couchbase DB Connector",
    version = 1,
    description = "Perform Couchbase DB operations: Get, Upsert, Replace, Delete documents and execute SQL++ / N1QL queries.",
    icon = "icon.svg",
    documentationRef = "https://github.com/camunda-community-hub/camunda-8-connector-couchbase/blob/main/README.md",
    propertyGroups = {
        @PropertyGroup(id = "authentication", label = "Authentication"),
        @PropertyGroup(id = "document",       label = "Document Settings"),
        @PropertyGroup(id = "operation",      label = "Operation"),
        @PropertyGroup(id = "query",          label = "Query Settings")
    }
)
public class CouchbaseConnector implements OutboundConnectorProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(CouchbaseConnector.class);

    private static final int     DEFAULT_MAX_ROWS      = 1000;
    private static final int     DEFAULT_QUERY_TIMEOUT = 30;
    private static final Pattern LIMIT_PATTERN         = Pattern.compile("\\bLIMIT\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECT_PATTERN        = Pattern.compile("^\\s*(SELECT|WITH)\\b", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final Function<CouchbaseAuthentication, Cluster> clusterSupplier;

    /** Default constructor used by the Camunda connector runtime. */
    public CouchbaseConnector() {
        this.clusterSupplier = auth -> {
            enforceTls(auth);
            return CouchbaseClientFactory.getCluster(
                auth.connectionString(), auth.username(), auth.password());
        };
    }

    /** Package-private constructor for unit testing with a mock cluster supplier. */
    CouchbaseConnector(Function<CouchbaseAuthentication, Cluster> clusterSupplier) {
        this.clusterSupplier = clusterSupplier;
    }

    // -------------------------------------------------------------------------
    // Operations
    // -------------------------------------------------------------------------

    @Operation(id = "getDocument", name = "Get Document")
    public Object getDocument(@Variable GetDocumentRequest request) {
        LOGGER.debug("getDocument — bucket={} collection={}", request.bucketName(), request.collectionName());
        Collection collection = resolveCollection(
            request.authentication(), request.bucketName(),
            request.scopeName(), request.collectionName());
        try {
            var result = collection.get(request.documentId());
            return Map.of(
                "id",      request.documentId(),
                "content", result.contentAsObject().toMap(),
                "cas",     result.cas()
            );
        } catch (DocumentNotFoundException e) {
            throw new ConnectorException("DOCUMENT_NOT_FOUND", "Document not found.", e);
        } catch (CollectionNotFoundException | ScopeNotFoundException e) {
            throw collectionNotFound(request.collectionName(), request.bucketName(), e);
        } catch (AuthenticationFailureException e) {
            throw authFailed(request.authentication(), e);
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            if (isCollectionNotFoundTimeout(e)) throw collectionNotFound(request.collectionName(), request.bucketName(), e);
            LOGGER.error("getDocument failed — bucket={}: {}", request.bucketName(), e.getMessage(), e);
            throw new ConnectorException("GET_FAILED", "Document retrieval failed.", e);
        }
    }

    @Operation(id = "upsertDocument", name = "Upsert Document")
    public Object upsertDocument(@Variable UpsertDocumentRequest request) {
        LOGGER.debug("upsertDocument — bucket={} collection={}", request.bucketName(), request.collectionName());
        Collection collection = resolveCollection(
            request.authentication(), request.bucketName(),
            request.scopeName(), request.collectionName());
        try {
            JsonObject json = toJsonObject(request.content());
            MutationResult result = collection.upsert(request.documentId(), json);
            return Map.of(
                "id",      request.documentId(),
                "cas",     result.cas(),
                "success", true
            );
        } catch (CollectionNotFoundException | ScopeNotFoundException e) {
            throw collectionNotFound(request.collectionName(), request.bucketName(), e);
        } catch (AuthenticationFailureException e) {
            throw authFailed(request.authentication(), e);
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            if (isCollectionNotFoundTimeout(e)) throw collectionNotFound(request.collectionName(), request.bucketName(), e);
            LOGGER.error("upsertDocument failed — bucket={}: {}", request.bucketName(), e.getMessage(), e);
            throw new ConnectorException("UPSERT_FAILED", "Upsert operation failed.", e);
        }
    }

    @Operation(id = "replaceDocument", name = "Replace Document")
    public Object replaceDocument(@Variable ReplaceDocumentRequest request) {
        LOGGER.debug("replaceDocument — bucket={} collection={}", request.bucketName(), request.collectionName());
        Collection collection = resolveCollection(
            request.authentication(), request.bucketName(),
            request.scopeName(), request.collectionName());
        try {
            JsonObject json = toJsonObject(request.content());
            MutationResult result = collection.replace(request.documentId(), json);
            return Map.of(
                "id",      request.documentId(),
                "cas",     result.cas(),
                "success", true
            );
        } catch (DocumentNotFoundException e) {
            throw new ConnectorException("DOCUMENT_NOT_FOUND", "Document not found. Use Upsert to create it.", e);
        } catch (CollectionNotFoundException | ScopeNotFoundException e) {
            throw collectionNotFound(request.collectionName(), request.bucketName(), e);
        } catch (AuthenticationFailureException e) {
            throw authFailed(request.authentication(), e);
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            if (isCollectionNotFoundTimeout(e)) throw collectionNotFound(request.collectionName(), request.bucketName(), e);
            LOGGER.error("replaceDocument failed — bucket={}: {}", request.bucketName(), e.getMessage(), e);
            throw new ConnectorException("REPLACE_FAILED", "Replace operation failed.", e);
        }
    }

    @Operation(id = "deleteDocument", name = "Delete Document")
    public Object deleteDocument(@Variable DeleteDocumentRequest request) {
        LOGGER.debug("deleteDocument — bucket={} collection={}", request.bucketName(), request.collectionName());
        Collection collection = resolveCollection(
            request.authentication(), request.bucketName(),
            request.scopeName(), request.collectionName());
        try {
            MutationResult result = collection.remove(request.documentId());
            return Map.of(
                "id",      request.documentId(),
                "cas",     result.cas(),
                "success", true
            );
        } catch (DocumentNotFoundException e) {
            throw new ConnectorException("DOCUMENT_NOT_FOUND", "Document not found.", e);
        } catch (CollectionNotFoundException | ScopeNotFoundException e) {
            throw collectionNotFound(request.collectionName(), request.bucketName(), e);
        } catch (AuthenticationFailureException e) {
            throw authFailed(request.authentication(), e);
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            if (isCollectionNotFoundTimeout(e)) throw collectionNotFound(request.collectionName(), request.bucketName(), e);
            LOGGER.error("deleteDocument failed — bucket={}: {}", request.bucketName(), e.getMessage(), e);
            throw new ConnectorException("DELETE_FAILED", "Delete operation failed.", e);
        }
    }

    @Operation(id = "query", name = "Execute N1QL Query")
    public Object query(@Variable QueryRequest request) {
        LOGGER.debug("query — operation=N1QL");
        Cluster cluster = clusterSupplier.apply(request.authentication());

        enforceStatementPolicy(request);

        int maxRows = request.maxRows() != null ? request.maxRows() : DEFAULT_MAX_ROWS;
        String queryText = injectLimitIfAbsent(request.query(), maxRows);
        QueryOptions opts = buildQueryOptions(request);

        try {
            QueryResult result = cluster.query(queryText, opts);
            List<Map<String, Object>> rows = result.rowsAsObject()
                .stream()
                .map(JsonObject::toMap)
                .toList();

            if (rows.size() >= maxRows) {
                LOGGER.warn("query returned {} rows, which hit the maxRows cap of {}. "
                    + "Add a LIMIT clause or raise maxRows if more data is needed.", rows.size(), maxRows);
            }

            return Map.of("rows", rows, "rowCount", rows.size());
        } catch (AuthenticationFailureException e) {
            throw authFailed(request.authentication(), e);
        } catch (ConnectorException e) {
            throw e;
        } catch (Exception e) {
            // Log only the class+message at ERROR to avoid embedding query text in log output;
            // full stack trace goes to DEBUG for trusted diagnostic environments.
            LOGGER.error("query failed ({}): {}", e.getClass().getSimpleName(), e.getMessage());
            LOGGER.debug("query failed — full exception", e);
            throw new ConnectorException("QUERY_FAILED", "Query execution failed.", e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Collection resolveCollection(
        CouchbaseAuthentication auth,
        String bucketName,
        String scopeName,
        String collectionName) {

        Cluster cluster = clusterSupplier.apply(auth);
        String scope      = (scopeName      == null || scopeName.isBlank())      ? "_default" : scopeName;
        String collection = (collectionName == null || collectionName.isBlank()) ? "_default" : collectionName;
        return cluster.bucket(bucketName).scope(scope).collection(collection);
    }

    private static void enforceTls(CouchbaseAuthentication auth) {
        String tls = auth.requireTls();
        if (tls != null && !tls.isBlank()
                && !"true".equalsIgnoreCase(tls)
                && !"false".equalsIgnoreCase(tls)) {
            throw new ConnectorException("INVALID_CONFIG",
                "Invalid value for Require TLS: '" + tls + "'. Must be 'true' or 'false'.");
        }
        if ("true".equalsIgnoreCase(tls)
                && !auth.connectionString().startsWith("couchbases://")) {
            throw new ConnectorException("TLS_REQUIRED",
                "TLS is required for this connector. Use couchbases:// in the connection string.");
        }
    }

    private static void enforceStatementPolicy(QueryRequest request) {
        String policy = request.statementPolicy();
        if (policy != null && !policy.isBlank()
                && !"ANY".equalsIgnoreCase(policy)
                && !"SELECT_ONLY".equalsIgnoreCase(policy)) {
            throw new ConnectorException("INVALID_CONFIG",
                "Invalid statementPolicy value: '" + policy + "'. Must be 'ANY' or 'SELECT_ONLY'.");
        }
        if ("SELECT_ONLY".equalsIgnoreCase(policy)) {
            if (!SELECT_PATTERN.matcher(request.query().trim()).find()) {
                throw new ConnectorException("QUERY_POLICY_VIOLATION",
                    "Only SELECT statements are permitted by the current statement policy.");
            }
        }
    }

    /**
     * Best-effort guardrail: appends LIMIT cap when no LIMIT keyword is detected (text scan,
     * not a SQL++ parser). A LIMIT inside a subquery or string literal satisfies the check
     * while the outer query remains unbounded — treat this as a convenience default, not a
     * hard enforcement mechanism.
     */
    private static String injectLimitIfAbsent(String rawQuery, int cap) {
        String q = rawQuery.trim().replaceAll(";\\s*$", "");
        if (!LIMIT_PATTERN.matcher(q).find()) {
            q = q + " LIMIT " + cap;
        }
        return q;
    }

    private static QueryOptions buildQueryOptions(QueryRequest request) {
        QueryOptions opts = QueryOptions.queryOptions();

        if (request.parameters() != null && !request.parameters().isEmpty()) {
            opts.parameters(JsonArray.from(request.parameters()));
        }

        int timeoutSecs = request.queryTimeoutSeconds() != null
            ? request.queryTimeoutSeconds()
            : DEFAULT_QUERY_TIMEOUT;
        opts.timeout(Duration.ofSeconds(timeoutSecs));

        if (request.scanConsistency() != null && !request.scanConsistency().isBlank()) {
            opts.scanConsistency(QueryScanConsistency.valueOf(request.scanConsistency()));
        }

        return opts;
    }

    private static ConnectorException collectionNotFound(String collection, String bucket, Exception cause) {
        return new ConnectorException("COLLECTION_NOT_FOUND",
            "Collection '" + collection + "' not found in bucket '" + bucket
                + "'. Verify the bucket, scope, and collection names.", cause);
    }

    private ConnectorException authFailed(CouchbaseAuthentication auth, AuthenticationFailureException cause) {
        CouchbaseClientFactory.evict(auth.connectionString(), auth.username(), auth.password());
        return new ConnectorException("AUTHENTICATION_FAILED",
            "Authentication failed. Verify the Couchbase username and password.", cause);
    }

    /**
     * Returns true when a KV timeout was caused by an unresolvable collection name.
     * The Couchbase SDK retries GetCollectionId until the KV timeout expires and then
     * throws AmbiguousTimeoutException / UnambiguousTimeoutException rather than
     * CollectionNotFoundException. The retry reason is embedded in the exception message.
     */
    private static boolean isCollectionNotFoundTimeout(Exception e) {
        String msg = e.getMessage();
        return msg != null && msg.contains("COLLECTION_NOT_FOUND");
    }

    @SuppressWarnings("unchecked")
    private JsonObject toJsonObject(Object content) {
        if (content == null) {
            return JsonObject.create();
        }
        if (content instanceof Map<?, ?> map) {
            return mapToJsonObject(map);
        }
        if (content instanceof String s) {
            try {
                return JsonObject.fromJson(s);
            } catch (Exception e) {
                throw new ConnectorException("INVALID_CONTENT",
                    "Content string is not valid JSON: " + e.getMessage(), e);
            }
        }
        throw new ConnectorException("INVALID_CONTENT",
            "Document content must be a Map (FEEL context) or a JSON string, got: "
                + content.getClass().getSimpleName());
    }

    @SuppressWarnings("unchecked")
    private JsonObject mapToJsonObject(Map<?, ?> map) {
        JsonObject obj = JsonObject.create();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            obj.put(entry.getKey().toString(), toJsonValue(entry.getValue()));
        }
        return obj;
    }

    @SuppressWarnings("unchecked")
    private Object toJsonValue(Object value) {
        if (value instanceof Map<?, ?> m) {
            return mapToJsonObject(m);
        }
        if (value instanceof List<?> l) {
            JsonArray arr = JsonArray.create();
            l.forEach(item -> arr.add(toJsonValue(item)));
            return arr;
        }
        return value;
    }
}
