# Kubernetes Deployment

This guide covers two deployment patterns for the Couchbase DB Connector:

- **Option A — Independent deployment**: A standalone pod running the official `camunda/connectors-bundle` image with the Couchbase connector injected at startup.
- **Option B — Camunda Helm chart**: Inject the connector JAR into the existing connectors pod managed by the Camunda 8.9 Helm chart, without modifying or replacing the official image.

Both patterns use the same mechanism: the `camunda/connectors-bundle` image ships a `/opt/custom/` directory and its `start.sh` already sets `-Dloader.path=/opt/custom/`. Any JAR placed there at startup is loaded alongside all out-of-the-box connectors. No image rebuild or override required.

---

## How it works

```
camunda/connectors-bundle:8.9.0
│
├── /opt/app/        ← built-in OOB connector JARs (HTTP, Slack, etc.)
└── /opt/custom/     ← loader.path; empty by default, mounted at runtime
```

An `initContainer` copies the Couchbase connector JAR from its own image into a shared `emptyDir` volume mounted at `/opt/custom/`. When the main container starts, `PropertiesLauncher` picks up every JAR in both `/opt/app/` and `/opt/custom/` — all OOB connectors plus the Couchbase connector.

---

## Prerequisites

- Docker with `buildx`
- `kubectl` configured against your target cluster
- Write access to `ghcr.io/camunda-community-hub/` (GitHub PAT with `write:packages` scope)
- For Option B: Helm 3, Camunda Helm repo added (`helm repo add camunda https://helm.camunda.io`)

---

## 1. Build and Push the Connector Image

The `Dockerfile` in this directory creates a minimal Alpine image that carries only the Couchbase connector fat JAR. It is used exclusively as the `initContainer` source — the actual connector runtime is always the official `camunda/connectors-bundle`.

### Build the fat JAR

```bash
# From the project root
mvn package -DskipTests
# Produces: target/connector-couchbase-<version>-with-dependencies.jar
```

### Build and push the connector image

```bash
IMAGE=ghcr.io/camunda-community-hub/connector-couchbase
VERSION=1.0.0

# Authenticate to GHCR
echo $GITHUB_TOKEN | docker login ghcr.io -u <github-username> --password-stdin

# Build (run from project root so the target/ directory is in build context)
docker build \
  --platform linux/amd64 \
  -t ${IMAGE}:${VERSION} \
  -t ${IMAGE}:latest \
  -f k8s/Dockerfile .

docker push ${IMAGE}:${VERSION}
docker push ${IMAGE}:latest
```

Multi-arch build:

```bash
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  --push \
  -t ${IMAGE}:${VERSION} \
  -t ${IMAGE}:latest \
  -f k8s/Dockerfile .
```

### Automate with GitHub Actions

Add this step to `.github/workflows/deploy.yaml` after the Maven publish step:

```yaml
- name: Log in to GHCR
  uses: docker/login-action@v3
  with:
    registry: ghcr.io
    username: ${{ github.actor }}
    password: ${{ secrets.GITHUB_TOKEN }}

- name: Build and push connector image
  uses: docker/build-push-action@v5
  with:
    context: .
    file: k8s/Dockerfile
    platforms: linux/amd64,linux/arm64
    push: true
    tags: |
      ghcr.io/camunda-community-hub/connector-couchbase:${{ github.event.release.tag_name }}
      ghcr.io/camunda-community-hub/connector-couchbase:latest
```

---

## Option A — Independent Deployment

Deploy a standalone connectors pod in its own namespace. The pod runs the official `camunda/connectors-bundle` with the Couchbase connector injected via initContainer.

### 1. Create the namespace

```bash
kubectl create namespace connectors
```

### 2. Create the Secret

```bash
kubectl create secret generic couchbase-connector-secrets \
  --namespace connectors \
  --from-literal=CAMUNDA_CLIENT_AUTH_CLIENT_SECRET=<your-connectors-client-secret>
```

### 3. Configure the Deployment

Edit `standalone/deployment.yaml` and replace the two placeholder values:

| Placeholder | Example |
|---|---|
| `<zeebe-rest-host>` | `camunda.example.com` |
| `<keycloak-host>` | `camunda.example.com` |

Also update the initContainer `image` tag to match the version you pushed.

### 4. Apply

```bash
kubectl apply -f k8s/standalone/secret.yaml
kubectl apply -f k8s/standalone/deployment.yaml
kubectl apply -f k8s/standalone/service.yaml
```

### 5. Verify

```bash
kubectl -n connectors get pods
kubectl -n connectors logs -l app=couchbase-connector --follow
```

In the logs you should see the connector registered:

```
Registered outbound connector: io.camunda.connector.couchbase.CouchbaseConnector (type=io.camunda:couchbase:1)
```

### TLS — self-signed or internal CA

If your Zeebe or Keycloak endpoint uses a certificate not trusted by the default JVM truststore, set `JAVA_OPTS` in the deployment:

```yaml
env:
  - name: JAVAX_NET_SSL_TRUSTSTORE
    value: "/certs/truststore.jks"
  - name: JAVAX_NET_SSL_TRUSTSTOREPASSWORD
    value: "changeit"
```

Mount the truststore via `extraVolumes` / `extraVolumeMounts`.

---

## Option B — Camunda Helm Chart (8.9)

Inject the Couchbase connector into the existing `connectors` component managed by the Camunda Helm chart. The official `camunda/connectors-bundle` image is used unchanged — only the `initContainers`, `extraVolumes`, and `extraVolumeMounts` values are added.

### Helm chart version

| Camunda version | Helm chart version |
|---|---|
| 8.9.x | `14.0.0` |

### Apply the values override

```bash
helm upgrade camunda camunda/camunda-platform \
  --version 14.0.0 \
  --namespace camunda \
  --reuse-values \
  -f k8s/helm/values-couchbase-connector.yaml
```

`--reuse-values` preserves every existing value in your live release and merges only the connector injection settings on top.

### What the override does

```yaml
connectors:
  initContainers:
    - name: copy-couchbase-connector
      image: ghcr.io/camunda-community-hub/connector-couchbase:1.0.0
      command: ["cp", "/opt/connector-couchbase.jar", "/opt/custom/connector-couchbase.jar"]
      volumeMounts:
        - name: custom-connectors
          mountPath: /opt/custom

  extraVolumes:
    - name: custom-connectors
      emptyDir: {}

  extraVolumeMounts:
    - name: custom-connectors
      mountPath: /opt/custom
```

The Helm chart renders the initContainer and volume inside the existing connectors `Deployment`. The official connectors image, the chart-generated `application.yaml` ConfigMap (Zeebe address, OIDC issuer, client credentials), and all other chart-managed settings remain completely unchanged.

### Adding multiple custom connectors

To add more custom connectors alongside Couchbase, extend `initContainers` with one entry per connector:

```yaml
connectors:
  initContainers:
    - name: copy-couchbase-connector
      image: ghcr.io/camunda-community-hub/connector-couchbase:1.0.0
      command: ["cp", "/opt/connector-couchbase.jar", "/opt/custom/connector-couchbase.jar"]
      volumeMounts:
        - name: custom-connectors
          mountPath: /opt/custom
    - name: copy-another-connector
      image: ghcr.io/your-org/connector-another:2.0.0
      command: ["cp", "/opt/connector-another.jar", "/opt/custom/connector-another.jar"]
      volumeMounts:
        - name: custom-connectors
          mountPath: /opt/custom

  extraVolumes:
    - name: custom-connectors
      emptyDir: {}

  extraVolumeMounts:
    - name: custom-connectors
      mountPath: /opt/custom
```

### Verify

```bash
kubectl -n camunda get pods -l app.kubernetes.io/component=connectors
kubectl -n camunda logs -l app.kubernetes.io/component=connectors --follow
```

---

## Couchbase Credentials

The Couchbase `connectionString`, `username`, and `password` are **not** connector environment variables. They are configured per service task in the BPMN model using Camunda Secrets (`= secrets.COUCHBASE_CONNECTION_STRING`, etc.) and stored in the Zeebe secret store — not in Kubernetes Secrets or the connector pod environment.

To register Camunda Secrets in a self-managed cluster, use Web Modeler's secret manager or the Zeebe API.
