package io.camunda.connector.couchbase.model;

import io.camunda.connector.generator.java.annotation.TemplateProperty;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record UpsertDocumentRequest(

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
        group = "document",
        label = "Document ID",
        description = "Unique key for the document. Created if it does not exist, replaced if it does."
    )
    String documentId,

    @NotNull
    @TemplateProperty(
        group = "document",
        label = "Document Content",
        description = "Document body as a FEEL context (= {\"key\": \"value\"}) or a JSON string",
        type = PropertyType.Text
    )
    Object content

) {}
