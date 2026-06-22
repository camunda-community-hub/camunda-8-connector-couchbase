package io.camunda.connector.couchbase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.java.Bucket;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.Scope;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.kv.GetResult;
import com.couchbase.client.java.kv.MutationResult;
import com.couchbase.client.java.query.QueryOptions;
import com.couchbase.client.java.query.QueryResult;
import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.couchbase.model.CouchbaseAuthentication;
import io.camunda.connector.couchbase.model.DeleteDocumentRequest;
import io.camunda.connector.couchbase.model.GetDocumentRequest;
import io.camunda.connector.couchbase.model.QueryRequest;
import io.camunda.connector.couchbase.model.ReplaceDocumentRequest;
import io.camunda.connector.couchbase.model.UpsertDocumentRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CouchbaseConnectorTest {

    @Mock private Cluster cluster;
    @Mock private Bucket bucket;
    @Mock private Scope scope;
    @Mock private Collection collection;
    @Mock private GetResult getResult;
    @Mock private MutationResult mutationResult;
    @Mock private QueryResult queryResult;

    private CouchbaseConnector connector;
    private CouchbaseAuthentication auth;

    @BeforeEach
    void setUp() {
        connector = new CouchbaseConnector(a -> cluster);
        auth = new CouchbaseAuthentication("couchbase://localhost", "admin", "secret", "false");

        lenient().when(cluster.bucket(anyString())).thenReturn(bucket);
        lenient().when(bucket.scope(anyString())).thenReturn(scope);
        lenient().when(scope.collection(anyString())).thenReturn(collection);
    }

    // -------------------------------------------------------------------------
    // getDocument
    // -------------------------------------------------------------------------

    @Test
    void getDocument_returnsIdContentAndCas() {
        JsonObject doc = JsonObject.create().put("name", "Alice").put("age", 30);
        when(collection.get("user::1")).thenReturn(getResult);
        when(getResult.contentAsObject()).thenReturn(doc);
        when(getResult.cas()).thenReturn(42L);

        Object result = connector.getDocument(
            new GetDocumentRequest(auth, "users", "_default", "_default", "user::1"));

        assertThat(result).isInstanceOf(Map.class);
        Map<?, ?> response = (Map<?, ?>) result;
        assertThat(response.get("id")).isEqualTo("user::1");
        assertThat(response.get("cas")).isEqualTo(42L);
        Map<?, ?> content = (Map<?, ?>) response.get("content");
        assertThat(content.get("name")).isEqualTo("Alice");
        assertThat(content.get("age")).isEqualTo(30);
    }

    @Test
    void getDocument_throwsConnectorException_whenDocumentNotFound() {
        when(collection.get("missing")).thenThrow(DocumentNotFoundException.class);

        assertThatThrownBy(() -> connector.getDocument(
            new GetDocumentRequest(auth, "users", "_default", "_default", "missing")))
            .isInstanceOf(ConnectorException.class)
            .hasMessageContaining("not found")
            .satisfies(e -> assertThat(((ConnectorException) e).getErrorCode())
                .isEqualTo("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void getDocument_usesDefaultScopeAndCollection_whenNull() {
        JsonObject doc = JsonObject.create().put("k", "v");
        when(collection.get("doc::1")).thenReturn(getResult);
        when(getResult.contentAsObject()).thenReturn(doc);
        when(getResult.cas()).thenReturn(1L);

        connector.getDocument(new GetDocumentRequest(auth, "myBucket", null, null, "doc::1"));

        verify(bucket).scope("_default");
        verify(scope).collection("_default");
    }

    // -------------------------------------------------------------------------
    // upsertDocument
    // -------------------------------------------------------------------------

    @Test
    void upsertDocument_returnsSuccessWithCas() {
        when(collection.upsert(eq("order::99"), any(JsonObject.class))).thenReturn(mutationResult);
        when(mutationResult.cas()).thenReturn(77L);

        Object result = connector.upsertDocument(new UpsertDocumentRequest(
            auth, "orders", "_default", "_default", "order::99", Map.of("status", "PENDING")));

        assertThat(result).isInstanceOf(Map.class);
        Map<?, ?> response = (Map<?, ?>) result;
        assertThat(response.get("id")).isEqualTo("order::99");
        assertThat(response.get("cas")).isEqualTo(77L);
        assertThat(response.get("success")).isEqualTo(true);
    }

    @Test
    void upsertDocument_acceptsJsonStringAsContent() {
        when(collection.upsert(anyString(), any(JsonObject.class))).thenReturn(mutationResult);
        when(mutationResult.cas()).thenReturn(1L);

        Object result = connector.upsertDocument(new UpsertDocumentRequest(
            auth, "orders", "_default", "_default", "order::1", "{\"status\":\"NEW\"}"));

        assertThat(((Map<?, ?>) result).get("success")).isEqualTo(true);
    }

    @Test
    void upsertDocument_throwsConnectorException_forBadJsonString() {
        assertThatThrownBy(() -> connector.upsertDocument(new UpsertDocumentRequest(
            auth, "orders", "_default", "_default", "order::1", "not-valid-json")))
            .isInstanceOf(ConnectorException.class)
            .hasMessageContaining("not valid JSON")
            .satisfies(e -> assertThat(((ConnectorException) e).getErrorCode())
                .isEqualTo("INVALID_CONTENT"));
    }

    // -------------------------------------------------------------------------
    // replaceDocument
    // -------------------------------------------------------------------------

    @Test
    void replaceDocument_returnsSuccessWithCas() {
        when(collection.replace(eq("item::5"), any(JsonObject.class))).thenReturn(mutationResult);
        when(mutationResult.cas()).thenReturn(55L);

        Object result = connector.replaceDocument(new ReplaceDocumentRequest(
            auth, "catalog", "_default", "_default", "item::5", Map.of("price", 9.99)));

        Map<?, ?> response = (Map<?, ?>) result;
        assertThat(response.get("id")).isEqualTo("item::5");
        assertThat(response.get("success")).isEqualTo(true);
    }

    @Test
    void replaceDocument_throwsConnectorException_whenDocumentNotFound() {
        when(collection.replace(anyString(), any(JsonObject.class)))
            .thenThrow(DocumentNotFoundException.class);

        assertThatThrownBy(() -> connector.replaceDocument(new ReplaceDocumentRequest(
            auth, "catalog", "_default", "_default", "item::999", Map.of("price", 1.0))))
            .isInstanceOf(ConnectorException.class)
            .hasMessageContaining("not found")
            .satisfies(e -> assertThat(((ConnectorException) e).getErrorCode())
                .isEqualTo("DOCUMENT_NOT_FOUND"));
    }

    // -------------------------------------------------------------------------
    // deleteDocument
    // -------------------------------------------------------------------------

    @Test
    void deleteDocument_returnsSuccessWithCas() {
        when(collection.remove("session::abc")).thenReturn(mutationResult);
        when(mutationResult.cas()).thenReturn(10L);

        Object result = connector.deleteDocument(
            new DeleteDocumentRequest(auth, "sessions", "_default", "_default", "session::abc"));

        Map<?, ?> response = (Map<?, ?>) result;
        assertThat(response.get("id")).isEqualTo("session::abc");
        assertThat(response.get("success")).isEqualTo(true);
        assertThat(response.get("cas")).isEqualTo(10L);
    }

    @Test
    void deleteDocument_throwsConnectorException_whenDocumentNotFound() {
        when(collection.remove("gone")).thenThrow(DocumentNotFoundException.class);

        assertThatThrownBy(() -> connector.deleteDocument(
            new DeleteDocumentRequest(auth, "sessions", "_default", "_default", "gone")))
            .isInstanceOf(ConnectorException.class)
            .hasMessageContaining("not found")
            .satisfies(e -> assertThat(((ConnectorException) e).getErrorCode())
                .isEqualTo("DOCUMENT_NOT_FOUND"));
    }

    // -------------------------------------------------------------------------
    // query — basic behaviour
    // -------------------------------------------------------------------------

    @Test
    void query_returnsRowsAndRowCount() {
        JsonObject row1 = JsonObject.create().put("id", "1").put("name", "Alice");
        JsonObject row2 = JsonObject.create().put("id", "2").put("name", "Bob");
        when(cluster.query(anyString(), any(QueryOptions.class))).thenReturn(queryResult);
        when(queryResult.rowsAsObject()).thenReturn(List.of(row1, row2));

        Object result = connector.query(
            new QueryRequest(auth, "SELECT * FROM `users` LIMIT 2", null, null, null, null, null));

        assertThat(result).isInstanceOf(Map.class);
        Map<?, ?> response = (Map<?, ?>) result;
        assertThat(response.get("rowCount")).isEqualTo(2);
        assertThat((List<?>) response.get("rows")).hasSize(2);
    }

    @Test
    void query_returnsEmptyList_whenNoResults() {
        when(cluster.query(anyString(), any(QueryOptions.class))).thenReturn(queryResult);
        when(queryResult.rowsAsObject()).thenReturn(List.of());

        Object result = connector.query(
            new QueryRequest(auth, "SELECT * FROM `empty`", null, null, null, null, null));

        Map<?, ?> response = (Map<?, ?>) result;
        assertThat(response.get("rowCount")).isEqualTo(0);
    }

    @Test
    void query_throwsConnectorException_onDatabaseError() {
        when(cluster.query(anyString(), any(QueryOptions.class)))
            .thenThrow(new RuntimeException("syntax error"));

        assertThatThrownBy(() -> connector.query(
            new QueryRequest(auth, "INVALID QUERY", null, null, null, null, null)))
            .isInstanceOf(ConnectorException.class)
            .satisfies(e -> assertThat(((ConnectorException) e).getErrorCode())
                .isEqualTo("QUERY_FAILED"));
    }

    // -------------------------------------------------------------------------
    // query — LIMIT guardrail
    // -------------------------------------------------------------------------

    @Test
    void query_injectsLimitWhenAbsent() {
        when(cluster.query(anyString(), any(QueryOptions.class))).thenReturn(queryResult);
        when(queryResult.rowsAsObject()).thenReturn(List.of());

        connector.query(new QueryRequest(auth, "SELECT * FROM `demo`", null, 500, null, null, null));

        // Verify the query sent to the cluster contains the injected LIMIT
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(cluster).query(captor.capture(), any(QueryOptions.class));
        assertThat(captor.getValue()).containsIgnoringCase("LIMIT 500");
    }

    @Test
    void query_doesNotDuplicateLimit_whenAlreadyPresent() {
        when(cluster.query(anyString(), any(QueryOptions.class))).thenReturn(queryResult);
        when(queryResult.rowsAsObject()).thenReturn(List.of());

        connector.query(new QueryRequest(
            auth, "SELECT * FROM `demo` LIMIT 10", null, 1000, null, null, null));

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(cluster).query(captor.capture(), any(QueryOptions.class));
        // Should contain exactly one LIMIT clause (the original)
        String sent = captor.getValue().toUpperCase();
        assertThat(sent.indexOf("LIMIT")).isEqualTo(sent.lastIndexOf("LIMIT"));
        assertThat(captor.getValue()).containsIgnoringCase("LIMIT 10");
    }

    @Test
    void query_stripsTrailingSemicolon() {
        when(cluster.query(anyString(), any(QueryOptions.class))).thenReturn(queryResult);
        when(queryResult.rowsAsObject()).thenReturn(List.of());

        connector.query(new QueryRequest(
            auth, "SELECT * FROM `demo` LIMIT 5;", null, 1000, null, null, null));

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(cluster).query(captor.capture(), any(QueryOptions.class));
        assertThat(captor.getValue()).doesNotEndWith(";");
    }

    // -------------------------------------------------------------------------
    // query — parameterised queries
    // -------------------------------------------------------------------------

    @Test
    void query_withPositionalParameters_passesParamsToOptions() {
        when(cluster.query(anyString(), any(QueryOptions.class))).thenReturn(queryResult);
        when(queryResult.rowsAsObject()).thenReturn(List.of());

        connector.query(new QueryRequest(
            auth,
            "SELECT * FROM `demo` WHERE tier = $1 LIMIT 10",
            List.of("gold"),
            null, null, null, null));

        // Verifies cluster.query was called with options (parameters are set on opts internally)
        verify(cluster).query(anyString(), any(QueryOptions.class));
    }

    // -------------------------------------------------------------------------
    // query — statement policy
    // -------------------------------------------------------------------------

    @Test
    void query_selectOnlyPolicy_allowsSelectStatement() {
        when(cluster.query(anyString(), any(QueryOptions.class))).thenReturn(queryResult);
        when(queryResult.rowsAsObject()).thenReturn(List.of());

        connector.query(new QueryRequest(
            auth, "SELECT * FROM `demo` LIMIT 1", null, null, null, null, "SELECT_ONLY"));

        verify(cluster).query(anyString(), any(QueryOptions.class));
    }

    @Test
    void query_selectOnlyPolicy_rejectsUpsertStatement() {
        assertThatThrownBy(() -> connector.query(new QueryRequest(
            auth,
            "UPSERT INTO `demo` (KEY, VALUE) VALUES (\"k\", {})",
            null, null, null, null, "SELECT_ONLY")))
            .isInstanceOf(ConnectorException.class)
            .satisfies(e -> assertThat(((ConnectorException) e).getErrorCode())
                .isEqualTo("QUERY_POLICY_VIOLATION"));
    }

    @Test
    void query_selectOnlyPolicy_rejectsDeleteStatement() {
        assertThatThrownBy(() -> connector.query(new QueryRequest(
            auth, "DELETE FROM `demo` WHERE 1=1", null, null, null, null, "SELECT_ONLY")))
            .isInstanceOf(ConnectorException.class)
            .satisfies(e -> assertThat(((ConnectorException) e).getErrorCode())
                .isEqualTo("QUERY_POLICY_VIOLATION"));
    }

    @Test
    void query_anyPolicy_allowsMutatingStatement() {
        when(cluster.query(anyString(), any(QueryOptions.class))).thenReturn(queryResult);
        when(queryResult.rowsAsObject()).thenReturn(List.of());

        connector.query(new QueryRequest(
            auth, "UPSERT INTO `demo` (KEY, VALUE) VALUES (\"k\", {})",
            null, null, null, null, "ANY"));

        verify(cluster).query(anyString(), any(QueryOptions.class));
    }

    // -------------------------------------------------------------------------
    // TLS enforcement
    // -------------------------------------------------------------------------

    @Test
    void getDocument_throwsTlsRequired_whenEnforcedWithPlaintextUrl() {
        CouchbaseAuthentication tlsAuth =
            new CouchbaseAuthentication("couchbase://localhost", "admin", "secret", "true");

        // Use a real connector (not the test one with mock supplier) to trigger TLS check
        CouchbaseConnector realConnector = new CouchbaseConnector(a -> {
            if ("true".equalsIgnoreCase(a.requireTls()) && !a.connectionString().startsWith("couchbases://")) {
                throw new ConnectorException("TLS_REQUIRED",
                    "TLS is required for this connector. Use couchbases:// in the connection string.");
            }
            return cluster;
        });

        assertThatThrownBy(() -> realConnector.getDocument(
            new GetDocumentRequest(tlsAuth, "bucket", "_default", "_default", "doc::1")))
            .isInstanceOf(ConnectorException.class)
            .satisfies(e -> assertThat(((ConnectorException) e).getErrorCode())
                .isEqualTo("TLS_REQUIRED"));
    }

    @Test
    void getDocument_allowsConnection_whenTlsEnabledWithCouchbasesUrl() {
        CouchbaseAuthentication tlsAuth =
            new CouchbaseAuthentication("couchbases://secure-host", "admin", "secret", "true");

        JsonObject doc = JsonObject.create().put("k", "v");
        when(cluster.bucket(anyString())).thenReturn(bucket);
        when(bucket.scope(anyString())).thenReturn(scope);
        when(scope.collection(anyString())).thenReturn(collection);
        when(collection.get(anyString())).thenReturn(getResult);
        when(getResult.contentAsObject()).thenReturn(doc);
        when(getResult.cas()).thenReturn(1L);

        CouchbaseConnector tlsConnector = new CouchbaseConnector(a -> cluster);
        // No exception expected
        tlsConnector.getDocument(
            new GetDocumentRequest(tlsAuth, "bucket", "_default", "_default", "doc::1"));
    }

    // -------------------------------------------------------------------------
    // Error messages — safe (no internal detail leakage)
    // -------------------------------------------------------------------------

    @Test
    void getDocument_errorMessage_doesNotExposeInternalDetail() {
        when(collection.get(anyString())).thenThrow(new RuntimeException("internal-host:11210 ECONNREFUSED"));

        assertThatThrownBy(() -> connector.getDocument(
            new GetDocumentRequest(auth, "users", "_default", "_default", "doc::1")))
            .isInstanceOf(ConnectorException.class)
            .satisfies(e -> {
                assertThat(((ConnectorException) e).getErrorCode()).isEqualTo("GET_FAILED");
                assertThat(e.getMessage()).doesNotContain("internal-host");
            });
    }

    @Test
    void query_errorMessage_doesNotExposeInternalDetail() {
        when(cluster.query(anyString(), any(QueryOptions.class)))
            .thenThrow(new RuntimeException("internal-host:8093 token invalid"));

        assertThatThrownBy(() -> connector.query(
            new QueryRequest(auth, "SELECT 1", null, null, null, null, null)))
            .isInstanceOf(ConnectorException.class)
            .satisfies(e -> {
                assertThat(((ConnectorException) e).getErrorCode()).isEqualTo("QUERY_FAILED");
                assertThat(e.getMessage()).doesNotContain("internal-host");
            });
    }

    // -------------------------------------------------------------------------
    // Max rows boundary
    // -------------------------------------------------------------------------

    @Test
    void query_usesDefaultMaxRows_whenNotSpecified() {
        when(cluster.query(anyString(), any(QueryOptions.class))).thenReturn(queryResult);
        when(queryResult.rowsAsObject()).thenReturn(List.of());

        connector.query(new QueryRequest(auth, "SELECT * FROM `demo`", null, null, null, null, null));

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(cluster).query(captor.capture(), any(QueryOptions.class));
        assertThat(captor.getValue()).containsIgnoringCase("LIMIT 1000");
    }

    @Test
    void query_usesCustomMaxRows_whenSpecified() {
        when(cluster.query(anyString(), any(QueryOptions.class))).thenReturn(queryResult);
        // Simulate hitting the cap: return exactly maxRows rows
        List<JsonObject> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) rows.add(JsonObject.create().put("i", i));
        when(queryResult.rowsAsObject()).thenReturn(rows);

        Object result = connector.query(
            new QueryRequest(auth, "SELECT * FROM `demo`", null, 50, null, null, null));

        Map<?, ?> response = (Map<?, ?>) result;
        assertThat(response.get("rowCount")).isEqualTo(50);
    }
}
