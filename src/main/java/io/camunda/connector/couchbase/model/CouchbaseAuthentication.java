package io.camunda.connector.couchbase.model;

import io.camunda.connector.generator.java.annotation.TemplateProperty;
import io.camunda.connector.generator.java.annotation.TemplateProperty.DropdownPropertyChoice;
import io.camunda.connector.generator.java.annotation.TemplateProperty.PropertyType;
import jakarta.validation.constraints.NotEmpty;

public record CouchbaseAuthentication(

    @NotEmpty
    @TemplateProperty(
        group = "authentication",
        label = "Connection String",
        description = "Couchbase connection string. E.g. couchbase://localhost or couchbases://cluster.cloud.couchbase.com"
    )
    String connectionString,

    @NotEmpty
    @TemplateProperty(
        group = "authentication",
        label = "Username",
        description = "Couchbase cluster username"
    )
    String username,

    @NotEmpty
    @TemplateProperty(
        group = "authentication",
        label = "Password",
        description = "Couchbase cluster password. Use a secret: = secrets.COUCHBASE_PASSWORD"
    )
    String password,

    @TemplateProperty(
        group = "authentication",
        label = "Require TLS (couchbases://)",
        description = "When set to Required, rejects plaintext couchbase:// connections. Recommended for production and Capella.",
        defaultValue = "false",
        optional = true,
        type = PropertyType.Dropdown,
        choices = {
            @DropdownPropertyChoice(label = "Disabled (development)", value = "false"),
            @DropdownPropertyChoice(label = "Required (production)", value = "true")
        }
    )
    String requireTls

) {}
