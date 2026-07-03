package io.camunda.connector.couchbase.model;

import io.camunda.connector.generator.java.annotation.TemplateProperty;
import io.camunda.connector.generator.java.annotation.TemplateProperty.DropdownPropertyChoice;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record QueryRequest(

    @Valid @NotNull
    CouchbaseAuthentication authentication,

    @NotEmpty
    @TemplateProperty(
        group = "query",
        label = "N1QL / SQL++ Query",
        description = "The SQL++ (N1QL) query to execute. Use $1, $2 ... for positional parameters.",
        type = PropertyType.Text
    )
    String query,

    @TemplateProperty(
        group = "query",
        label = "Positional Parameters",
        description = "Optional ordered list of parameter values, e.g. = [\"value1\", 42]. Maps to $1, $2 in the query.",
        optional = true
    )
    List<Object> parameters,

    @Min(1)
    @TemplateProperty(
        group = "query",
        label = "Max Rows",
        description = "Maximum rows to return (must be ≥ 1). A LIMIT clause is automatically appended if the query has none. Keep this low — all rows are loaded into memory before returning. Default: 1000.",
        optional = true
    )
    Integer maxRows,

    @Min(1)
    @TemplateProperty(
        group = "query",
        label = "Query Timeout (seconds)",
        description = "Server-side query execution timeout in seconds (must be ≥ 1). Default: 30.",
        optional = true
    )
    Integer queryTimeoutSeconds,

    @TemplateProperty(
        group = "query",
        label = "Scan Consistency",
        description = "NOT_BOUNDED: fastest, may see stale data. REQUEST_PLUS: consistent with all mutations before the request.",
        defaultValue = "NOT_BOUNDED",
        optional = true,
        type = PropertyType.Dropdown,
        choices = {
            @DropdownPropertyChoice(label = "Not Bounded (fastest)", value = "NOT_BOUNDED"),
            @DropdownPropertyChoice(label = "Request Plus (consistent)", value = "REQUEST_PLUS")
        }
    )
    String scanConsistency,

    @TemplateProperty(
        group = "query",
        label = "Statement Policy",
        description = "SELECT_ONLY (default, recommended): restricts to read-only SELECT/WITH statements. ANY: permits all N1QL statements including INSERT/UPDATE/DELETE.",
        defaultValue = "SELECT_ONLY",
        optional = true,
        type = PropertyType.Dropdown,
        choices = {
            @DropdownPropertyChoice(label = "SELECT only (read-only, recommended)", value = "SELECT_ONLY"),
            @DropdownPropertyChoice(label = "Any statement", value = "ANY")
        }
    )
    String statementPolicy

) {}
