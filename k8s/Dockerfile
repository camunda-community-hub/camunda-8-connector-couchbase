# Minimal image that carries only the connector JAR.
# Used as the source in an initContainer to inject the JAR into
# the official camunda/connectors-bundle image at /opt/custom/ — the
# loader.path already configured in the bundle's start.sh.
#
# Build:
#   mvn package -DskipTests
#   docker build -f k8s/Dockerfile -t ghcr.io/camunda-community-hub/connector-couchbase:<version> .

FROM alpine:3.21
RUN addgroup -g 1000 -S connector && adduser -u 1000 -S connector -G connector
COPY --chown=connector:connector target/connector-couchbase-*-with-dependencies.jar /opt/connector-couchbase.jar
USER 1000
