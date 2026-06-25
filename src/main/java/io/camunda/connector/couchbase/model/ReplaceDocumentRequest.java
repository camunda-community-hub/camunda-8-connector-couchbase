package io.camunda.connector.couchbase.model;

import io.camunda.connector.generator.java.annotation.TemplateProperty;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record ReplaceDocumentRequest(

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
        description = "The unique key of the existing document to replace. Fails if the document does not exist."
    )
    String documentId,

    @NotNull
    @TemplateProperty(
        group = "operation",
        label = "Document Content",
        description = "New full document body as a FEEL context (= {\"key\": \"value\"}) or a JSON string. Replaces the entire document.",
        type = PropertyType.Text
    )
    Object content

) {}
