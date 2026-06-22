package io.camunda.connector.couchbase.model;

import io.camunda.connector.generator.java.annotation.TemplateProperty;
import io.camunda.connector.generator.java.annotation.TemplateProperty.DropdownPropertyChoice;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyType;
import jakarta.validation.Valid;
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

    @TemplateProperty(
        group = "query",
        label = "Max Rows",
        description = "Maximum rows to return. A LIMIT clause is automatically appended if the query has none. Default: 1000.",
        optional = true
    )
    Integer maxRows,

    @TemplateProperty(
        group = "query",
        label = "Query Timeout (seconds)",
        description = "Server-side query execution timeout in seconds. Default: 30.",
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
        description = "ANY: all N1QL statements allowed. SELECT_ONLY: restricts to read-only SELECT/WITH statements.",
        defaultValue = "ANY",
        optional = true,
        type = PropertyType.Dropdown,
        choices = {
            @DropdownPropertyChoice(label = "Any statement", value = "ANY"),
            @DropdownPropertyChoice(label = "SELECT only (read-only)", value = "SELECT_ONLY")
        }
    )
    String statementPolicy

) {}
