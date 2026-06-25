package io.camunda.connector.couchbase.model;

import io.camunda.connector.generator.java.annotation.TemplateProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record DeleteDocumentRequest(

    @Valid @NotNull
    CouchbaseAuthentication authentication,

    @NotEmpty
    @TemplateProperty(
        group = "document",
        label = "Bucket Name",
        description = "Name of the Couchbase bucket"
    )
    String bucketName,

    @TemplateProperty(
        group = "document",
        label = "Scope Name",
        description = "Scope within the bucket (leave blank for _default)",
        defaultValue = "_default",
        optional = true
    )
    String scopeName,

    @TemplateProperty(
        group = "document",
        label = "Collection Name",
        description = "Collection within the scope (leave blank for _default)",
        defaultValue = "_default",
        optional = true
    )
    String collectionName,

    @NotEmpty
    @TemplateProperty(
        group = "operation",
        label = "Document ID",
        description = "The unique key of the document to delete"
    )
    String documentId

) {}
