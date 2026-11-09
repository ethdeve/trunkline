# Kubernetes Deployment

<!-- 版本信息 -->
> **Doc Version**: 1.0.2  
> **Last Updated**: 2026-05-21  
> **Git Commit**: 61384b4a  
> **Author**: Lincoln
<!-- /版本信息 -->



Trunkline supports deployment in Kubernetes clusters, providing rolling updates and other enterprise-grade features. This document details how to deploy Trunkline in a K8s environment.

> **Multi-replica requires a shared database and shared-state switches first**: with an external
> database in place multi-replica is usable (see `docs/en/deployment/database.md`), but the
> cross-replica shared-state switches **must** be enabled at the same time — otherwise each replica
> degrades into an isolated single-node instance. See `docs/en/deployment/multi-replica.md`.

## Deployment Artifacts Provided by the Repository (recommended)

`deploy/k8s/` ships **`Kustomize`** artifacts (`base` plus `dev` / `prod` overlays); no extra toolchain
required:

```bash
# Preview the rendered output (kustomize is built into kubectl — no helm needed)
kubectl kustomize deploy/k8s/overlays/prod

# Deploy
kubectl apply -k deploy/k8s/overlays/prod
```

Why `Kustomize` rather than Helm: it is built into `kubectl`, so neither CI nor users need to install
helm; the manifests are plain YAML and can be asserted directly by unit tests (see
`K8sManifestTest`).

The artifacts include: Deployment, Service, Ingress, ConfigMap, ServiceAccount, PodDisruptionBudget,
a schema migration Job, plus liveness / readiness / startup probes, resource requests/limits, and a
non-root security context (`runAsUser: 10010`, matching the user already inside the image).

### The schema migration Job runs first (deployment order)

On the PostgreSQL path application pods only `validate` (H2 keeps `update`, see `database.md`), so **every table-structure
change is performed by the schema migration Job** — the deployment order is therefore **Job finishes →
application pods start**. The Job uses a dedicated entry point
(`SPRING_PROFILES_ACTIVE=prod,json-logs,migrate`, see issue #193) and runs the migration once, on a
single instance:

- It exits by itself when done and its **exit code reflects the outcome** (0 = startup and migration
  both passed; non-zero = failure), so the Job's `Complete` / `Failed` is a trustworthy signal. The
  manifest therefore sets **no** `activeDeadlineSeconds` fallback — "kill it after N seconds" disguises
  a failure as a timeout and makes the outcome unreadable.
- The Job also fails when the migration fails or when that instance's configuration is unacceptable
  (e.g. the production startup check): the same criteria as the application pods, and it fails before
  traffic is switched over.
- That mode still boots the application context (non-web, listening on no port), so its startup cost is
  on the same order as an application pod.

**Two things you must do before deploying**:

1. **Create the `trunkline-secrets` Secret** — the repository deliberately ships no Secret entity, so
   that credentials never land in git:

   ```bash
   kubectl create secret generic trunkline-secrets -n trunkline \
     --from-literal=JWT_SECRET="$(openssl rand -base64 48)" \
     --from-literal=INITIAL_ADMIN_PASSWORD='<strong password>' \
     --from-literal=REDIS_PASSWORD='<redis password>'
   ```

2. **If you expose the service through Ingress, configure client-IP trust** — trusted proxies on the
   controller side *and* `trunkline.security.trusted-proxies.*` on the application side are both
   required. Otherwise the `clientIp` the application sees is the gateway IP, and rate limiting and
   auditing are both distorted (in that case switch the limiting dimension to `api-key` / `tenant`).

### Log Shape (Structured stdout)

The `deploy/k8s/base` manifests set `SPRING_PROFILES_ACTIVE` to `prod,json-logs`. `json-logs` is a
profile in `logback-spring.xml` that makes **stdout emit one line of structured JSON per event**, with
`timestamp` / `level` / `logger` / `thread` / `message`, plus `traceId` / `spanId` when a tracing context
exists and `instanceId` when `INSTANCE_ID` is injected (so logs can be searched per replica).

Without that profile (e.g. running `java -jar` locally) **behavior is unchanged**: stdout carries both a
text line and a JSON line, i.e. every event is written twice. File logging still goes to `logs/` (an
emptyDir inside containers).

If your deployment does not use the manifests from this repository, the equivalent is to add the profile
to the process: `SPRING_PROFILES_ACTIVE=prod,json-logs`.

## Multi-Replica Status (Known Constraints)

The **storage prerequisite is in place** (external database support, #160). The capabilities below are
still per replica; enable the shared-state switches described in
`docs/en/deployment/multi-replica.md`, or knowingly accept their semantics:

| Capability | Current state | Multi-replica consequence |
|------------|---------------|---------------------------|
| Rate limiting | All state in-JVM; no distributed rate limiter; key dimension is configurable (`model.rate-limit.key-dimension`) | Allowed throughput ≈ configured value × replicas, window resets counted per Pod. When the key dimension is meaningless (no L7 proxy) counting by IP counts noise |
| Quota ledger | Off by default; Redis atomic counting once `quota.distributed.enabled` is on | Left off, overuse is about N times the limit |
| JWT blacklist | Falls back to local storage by default; Redis available | Left off, revocation only applies on the issuing Pod (a security gap) |
| API-key cache | Per-replica mirror, refreshed from shared storage every `cache-refresh-interval-seconds` (default 60s) | Revocations/creates take effect across Pods within one refresh interval |
| Role-permission cache | Caffeine `expireAfterWrite=5min`, cleared only in this process | Permission changes take up to 5 minutes to converge (bounded, documented) |
| SSE / WebSocket | Event source is an in-process `Sinks.Many`; no Redis pub/sub | Clients only see events from the Pod they are connected to (issue #164) |
| Scheduled tasks | Classified: file writers name per replica, externally-observable effects take a cross-replica lock, per-replica state refreshes run everywhere | See #163 |
| Graceful shutdown | `server.shutdown=graceful` + 30s shutdown phase; artifacts use a 45s grace period (larger than the phase) | Rolling updates do not truncate in-flight streaming responses |

### Historical Constraint and Verified Evidence

Earlier versions supported only the H2 embedded single-file database; sharing one data volume made the
second and third Pods unable to open it, entering a crash loop. Verified: two JVM processes opening
`jdbc:h2:file:./data/trunkline` concurrently causes the second to fail with
`Database may be already in use` (H2 error 90020). External shared database support is tracked in
issue #160.

### Real-Cluster Verification (2026-10)

The `deploy/k8s/base` manifests were exercised on Docker Desktop's built-in Kubernetes (v1.36.1) together
with a Redis fixture:

- **The migration Job completes**: `Complete 1/1` in 82 seconds, exit code 0 — the migration really
  runs, and the process exits by itself afterwards. (The manifest passed
  `trunkline.migration.exit-after-run=true` back then; see #195. Since #193 this is handled by the
  `migrate` entry point, which also makes the exit code reflect the outcome.)
- **A three-replica Deployment reaches `3/3 Available`** (pods `READY`, zero restarts). Three
  prerequisites are required, each fatal if missing:
  1. a Service named `redis` in the namespace (the ConfigMap points `REDIS_HOST` at it);
  2. `trunkline-secrets` must hold **strong** values: `JWT_SECRET` at least 32 bytes *after* base64
     decoding (e.g. `openssl rand -base64 48`) and a strong `INITIAL_ADMIN_PASSWORD`, otherwise the
     production startup check fails fast;
  3. the probe paths `/actuator/health/liveness` and `/actuator/health/readiness` must be reachable
     anonymously (allowed by default; see #203).

Not verified yet (do not treat as established): cross-replica **state consistency** (each replica gets its
own emptyDir at `/app/data`, and a correct multi-replica setup still needs a shared database), PDB
eviction, and the published image `sodlinken/jairouter:latest` itself (an image built from the same jar was
used instead).

### Remaining Work for Horizontal Scaling

Done: external shared database (#160), shared-state switches and startup gating (#162), scheduled-task
classification (#163), configurable rate-limit key dimension (#161, partial), secret/password strength
false-positive fixes (#169 / #171).

Still open:

1. **Distributed rate-limit counting** (#161 remainder: Redis atomic counting and its degradation
   policy; only meaningful once the key dimension is correct)
2. Cross-Pod real-time event broadcast (#164)
3. Versioned schema migration — **done** (#191: on the PostgreSQL path Flyway's baseline plus
   `ddl-auto: validate` own the schema, see `database.md`; #192: H2 got versioned migrations too and
   the two start-up patch components were deleted, with H2 keeping `update`), and so is
   the **dedicated migration entry point** (#193: the `migrate` profile exits when done and its exit
   code reflects the outcome, so the Job needs no `activeDeadlineSeconds` fallback).
4. stdout structured logging — **done** (#211's `json-logs` profile); connection-pool / thread-pool
   externalization is **done** (#188 for the outbound WebClient, #189 for the tracing scheduler)

### Existing Foundation: Redis Shared-State Implementations

The repository already ships Redis shared-state implementations (quota `RedisCounterBackend` + Lua, JWT blacklist, token persistence, state persistence Tier1), but they are **all off by default**. The comment in `config/persistence/state-persistence-base.yml` already says "enable for cluster deployment". Relevant config keys:

| Config key | Purpose | Default |
|------------|---------|---------|
| `trunkline.persistence.redis.enabled` | State persistence Tier1 (circuit-breaker / load-balancer state shared across nodes) | `false` |
| `trunkline.quota.distributed.enabled` | Quota Redis atomic counting | `false` |
| `trunkline.security.jwt.persistence.redis.enabled` | JWT token persistence | `false` |
| `trunkline.security.jwt.blacklist.redis.enabled` | JWT blacklist across nodes | `false` |
| `jairouter.security.cache.redis.enabled` | Security-cache `ReactiveRedisTemplate` (@Primary) | `false` |

## Kubernetes Deployment Overview

### Features

- **High Availability** (planned): multi-instance deployment and automatic failover require shared database and shared-state wiring first; `replicas` must currently be 1
- **Auto-scaling** (planned): HPA would raise the replica count, which is incompatible with the H2 single-file database — do not enable
- **Rolling Updates**: zero-downtime updates (note: no graceful-shutdown settings exist today, so in-flight streaming responses may be truncated)
- **Service Discovery**: Built-in service discovery and load balancing
- **Configuration Management**: Configuration management using ConfigMap and Secret
- **Persistent Storage**: Support for PVC to persist configurations, logs, and data

### Architecture Diagram

``mermaid
graph TB
    subgraph "Kubernetes Cluster"
        subgraph "Ingress Layer"
            A[Ingress Controller]
        end
        
        subgraph "Service Layer"
            B[Trunkline Service]
        end
        
        subgraph "Application Layer"
            C[Trunkline Pod 1]
            D[Trunkline Pod 2]
            E[Trunkline Pod N]
        end
        
        subgraph "Storage Layer"
            F[ConfigMap]
            G[Secret]
            H[PVC - Config]
            I[PVC - Logs]
        end
        
        subgraph "Monitoring Layer"
            J[Prometheus]
            K[Grafana]
            L[ServiceMonitor]
        end
    end
    
    A --> B
    B --> C
    B --> D
    B --> E
    
    C --> F
    C --> G
    C --> H
    D --> F
    D --> G
    D --> I
    
    L --> C
    L --> D
    L --> E
    L --> J
    J --> K
```

## Prerequisites

### 1. Kubernetes Cluster Requirements

| Component | Minimum Requirement | Recommended Configuration | Description |
|-----------|---------------------|----------------------------|-------------|
| **Kubernetes Version** | 1.20+ | 1.24+ | Support for latest features |
| **Node Count** | 3 nodes | 5+ nodes | Including master and worker |
| **Node Configuration** | 4C8G | 8C16G | Per worker node |
| **Storage** | 100GB | 500GB SSD | Persistent storage |
| **Network** | CNI Plugin | Calico/Flannel | Network communication |

### 2. Required Components

| Component | Version Requirement | Installation Method | Purpose |
|-----------|---------------------|---------------------|---------|
| **kubectl** | 1.20+ | Official Installation | Cluster management tool |
| **Helm** | 3.0+ | Official Installation | Package manager |
| **Ingress Controller** | Nginx/Traefik | Helm Chart | External access |
| **Cert-Manager** | 1.0+ | Helm Chart | SSL certificate management |
| **Prometheus Operator** | 0.50+ | Helm Chart | Monitoring system |
| **Grafana** | 8.0+ | Helm Chart | Monitoring visualization |

### 3. Storage Class

```bash
# Check available storage classes
kubectl get storageclass

# If there's no default storage class, create one
kubectl patch storageclass <storage-class-name> -p '{"metadata": {"annotations":{"storageclass.kubernetes.io/is-default-class":"true"}}}'
```

## Basic Deployment

### 1. Create Namespace

```yaml
# namespace.yaml
apiVersion: v1
kind: Namespace
metadata:
  name: trunkline
  labels:
    name: trunkline
```

```bash
kubectl apply -f namespace.yaml
```

### 2. Create ConfigMap

```yaml
# configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: trunkline-config
  namespace: trunkline
data:
  application.yml: |
    server:
      port: 8080
    
    model:
      load-balance:
        type: round-robin
      rate-limit:
        enabled: true
        algorithm: token-bucket
        capacity: 1000
        rate: 100
      services:
        chat:
          instances:
            - name: "llama3.2:3b"
              base-url: "http://ollama-service:11434"
              path: "/v1/chat/completions"
              weight: 1
    
    management:
      endpoints:
        web:
          exposure:
            include: health,info,metrics,prometheus
      metrics:
        export:
          prometheus:
            enabled: true
    
    logging:
      level:
        org.unreal.modelrouter: INFO
      file:
        name: /app/logs/trunkline.log
```

```bash
kubectl apply -f configmap.yaml
```

### 3. Create Secret

```yaml
# secret.yaml
apiVersion: v1
kind: Secret
metadata:
  name: trunkline-secret
  namespace: trunkline
type: Opaque
data:
  # Base64 encoded keys
  api-key: eW91ci1hcGkta2V5LWhlcmU=  # your-api-key-here
  database-password: cGFzc3dvcmQ=     # password
  redis-password: cGFzc3dvcmQ=     # your-redis-password (base64)
```

```bash
kubectl apply -f secret.yaml
```

### 4. Create PVC

```yaml
# pvc.yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: trunkline-config-pvc
  namespace: trunkline
spec:
  accessModes:
    - ReadWriteMany
  resources:
    requests:
      storage: 1Gi
  storageClassName: nfs-client  # Adjust according to actual situation

---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: trunkline-logs-pvc
  namespace: trunkline
spec:
  accessModes:
    - ReadWriteMany
  resources:
    requests:
      storage: 10Gi
  storageClassName: nfs-client  # Adjust according to actual situation

---
# Primary H2 database volume (must be mounted at /app/data)
# ReadWriteOnce is itself a hard constraint for replicas: 1:
# the H2 embedded single-file database cannot be opened by multiple processes,
# so sharing it across Pods causes a crash loop.
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: trunkline-data-pvc
  namespace: trunkline
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 10Gi
  storageClassName: standard  # Adjust according to actual situation
```

```bash
kubectl apply -f pvc.yaml
```

### 5. Create Deployment

> **⚠️ Warning: `replicas` must be 1**
>
> The only supported store is the H2 embedded single-file database. Verified: two JVM processes opening the same file database concurrently causes the second process to fail immediately (`Database may be already in use`, error 90020). Sharing one PVC makes the second and third Pods crash-loop; separate volumes give each Pod its own data, so request round-robin produces random 401s / state drift. `replicas > 1` requires external shared database adaptation first (issue #160).
>
> `/app/data` must be mounted on a persistent volume (`ReadWriteOnce`). With `readOnlyRootFilesystem: true` and no `/app/data` mount, the primary H2 database cannot be created, so the Pod **fails to start even with `replicas: 1`**.

```yaml
# deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: trunkline
  namespace: trunkline
  labels:
    app: trunkline
spec:
  # Must be 1: the H2 embedded single-file database does not support multi-replica (see warning above and issue #160)
  replicas: 1
  selector:
    matchLabels:
      app: trunkline
  template:
    metadata:
      labels:
        app: trunkline
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: "/actuator/prometheus"
    spec:
      containers:
      - name: trunkline
        image: sodlinken/jairouter:latest
        ports:
        - containerPort: 8080
          name: http
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "prod"
        - name: JAVA_OPTS
          value: "-Xms512m -Xmx1024m -XX:+UseG1GC -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
        # Secret name/key must match the Secret created above (trunkline-secret)
        - name: API_KEY
          valueFrom:
            secretKeyRef:
              name: trunkline-secret
              key: api-key
        # Redis environment variables: optional for single replica, required for multi-replica
        - name: REDIS_HOST
          value: "your-redis-host"
        - name: REDIS_PORT
          value: "6379"
        - name: REDIS_PASSWORD
          valueFrom:
            secretKeyRef:
              name: trunkline-secret
              key: redis-password
        volumeMounts:
        - name: config-volume
          mountPath: /app/config
          readOnly: true
        - name: logs-volume
          mountPath: /app/logs
        - name: config-store-volume
          mountPath: /app/config-store
        # Primary H2 database directory — must be mounted
        # (otherwise the database cannot be created under readOnlyRootFilesystem)
        - name: data-volume
          mountPath: /app/data
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
        securityContext:
          allowPrivilegeEscalation: false
          readOnlyRootFilesystem: true
          capabilities:
            drop:
            - ALL
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 30
          timeoutSeconds: 10
          failureThreshold: 3
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
          timeoutSeconds: 5
          failureThreshold: 3
      volumes:
      - name: config-volume
        configMap:
          name: trunkline-config
      - name: logs-volume
        persistentVolumeClaim:
          claimName: trunkline-logs-pvc
      - name: config-store-volume
        persistentVolumeClaim:
          claimName: trunkline-config-pvc
      - name: data-volume
        persistentVolumeClaim:
          claimName: trunkline-data-pvc
      restartPolicy: Always
```

```bash
kubectl apply -f deployment.yaml
```

### 6. Create Service

```yaml
# service.yaml
apiVersion: v1
kind: Service
metadata:
  name: trunkline-service
  namespace: trunkline
  labels:
    app: trunkline
spec:
  selector:
    app: trunkline
  ports:
  - name: http
    port: 80
    targetPort: 8080
    protocol: TCP
  type: ClusterIP

---
# If NodePort access is needed
apiVersion: v1
kind: Service
metadata:
  name: trunkline-nodeport
  namespace: trunkline
  labels:
    app: trunkline
spec:
  selector:
    app: trunkline
  ports:
  - name: http
    port: 80
    targetPort: 8080
    nodePort: 30080
    protocol: TCP
  type: NodePort
```

```bash
kubectl apply -f service.yaml
```

### 7. Create Ingress

```yaml
# ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: trunkline-ingress
  namespace: trunkline
  annotations:
    kubernetes.io/ingress.class: nginx
    nginx.ingress.kubernetes.io/rewrite-target: /
    nginx.ingress.kubernetes.io/ssl-redirect: "false"
    nginx.ingress.kubernetes.io/proxy-body-size: "10m"
    nginx.ingress.kubernetes.io/proxy-read-timeout: "300"
    nginx.ingress.kubernetes.io/proxy-send-timeout: "300"
spec:
  rules:
  - host: trunkline.example.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: trunkline-service
            port:
              number: 80
  # If TLS certificate is available
  # tls:
  # - hosts:
  #   - trunkline.example.com
  #   secretName: trunkline-tls
```

```bash
kubectl apply -f ingress.yaml
```

## Auto-scaling

### 1. Horizontal Pod Autoscaler (HPA)

```yaml
# hpa.yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: trunkline-hpa
  namespace: trunkline
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: trunkline
  minReplicas: 3
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80
  # Custom metrics (requires Prometheus Adapter)
  - type: Pods
    pods:
      metric:
        name: http_requests_per_second
      target:
        type: AverageValue
        averageValue: "100"
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300
      policies:
      - type: Percent
        value: 10
        periodSeconds: 60
    scaleUp:
      stabilizationWindowSeconds: 60
      policies:
      - type: Percent
        value: 50
        periodSeconds: 60
```

```bash
kubectl apply -f hpa.yaml
```

### 2. Vertical Pod Autoscaler (VPA)

```yaml
# vpa.yaml
apiVersion: autoscaling.k8s.io/v1
kind: VerticalPodAutoscaler
metadata:
  name: trunkline-vpa
  namespace: trunkline
spec:
  targetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: trunkline
  updatePolicy:
    updateMode: "Auto"  # Or "Off", "Initial", "Recreation"
  resourcePolicy:
    containerPolicies:
    - containerName: trunkline
      minAllowed:
        cpu: 100m
        memory: 128Mi
      maxAllowed:
        cpu: 2
        memory: 2Gi
      controlledResources: ["cpu", "memory"]
```

```bash
kubectl apply -f vpa.yaml
```

## Monitoring Integration

### 1. ServiceMonitor

```yaml
# servicemonitor.yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: trunkline-monitor
  namespace: trunkline
  labels:
    app: trunkline
spec:
  selector:
    matchLabels:
      app: trunkline
  endpoints:
  - port: http
    path: /actuator/prometheus
    interval: 30s
    scrapeTimeout: 10s
```

```bash
kubectl apply -f servicemonitor.yaml
```

### 2. PrometheusRule

```yaml
# prometheusrule.yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: trunkline-rules
  namespace: trunkline
  labels:
    app: trunkline
spec:
  groups:
  - name: trunkline.rules
    rules:
    - alert: JAiRouterDown
      expr: up{job="trunkline-service"} == 0
      for: 1m
      labels:
        severity: critical
      annotations:
        summary: "JAiRouter instance is down"
        description: "JAiRouter instance {{ $labels.instance }} has been down for more than 1 minute."
    
    - alert: JAiRouterHighErrorRate
      expr: rate(http_server_requests_total{status=~"5.."}[5m]) / rate(http_server_requests_total[5m]) > 0.1
      for: 2m
      labels:
        severity: warning
      annotations:
        summary: "JAiRouter high error rate"
        description: "JAiRouter error rate is above 10% for more than 2 minutes."
    
    - alert: JAiRouterHighMemoryUsage
      expr: container_memory_usage_bytes{pod=~"trunkline-.*"} / container_spec_memory_limit_bytes > 0.9
      for: 5m
      labels:
        severity: warning
      annotations:
        summary: "JAiRouter high memory usage"
        description: "JAiRouter memory usage is above 90% for more than 5 minutes."
```

```bash
kubectl apply -f prometheusrule.yaml
```

## Helm Chart Deployment

### 1. Create Helm Chart

```bash
# Create Chart directory structure
mkdir -p trunkline-chart/{templates,charts}
cd trunkline-chart
```

### 2. Chart.yaml

```yaml
# Chart.yaml
apiVersion: v2
name: trunkline
description: A Helm chart for JAiRouter AI model routing gateway
type: application
version: 3.1.1
appVersion: "3.1.1"
keywords:
  - ai
  - router
  - gateway
  - load-balancer
home: https://github.com/Lincoln-cn/JAiRouter
sources:
  - https://github.com/Lincoln-cn/JAiRouter
maintainers:
  - name: JAiRouter Team
    email: team@trunkline.com
```

### 3. values.yaml

```yaml
# values.yaml
# Must be 1: the H2 embedded single-file database does not support multi-replica (issue #160)
replicaCount: 1

image:
  repository: sodlinken/jairouter
  pullPolicy: IfNotPresent
  tag: "latest"

nameOverride: ""
fullnameOverride: ""

serviceAccount:
  create: true
  annotations: {}
  name: ""

podAnnotations:
  prometheus.io/scrape: "true"
  prometheus.io/port: "8080"
  prometheus.io/path: "/actuator/prometheus"

podSecurityContext:
  fsGroup: 10010

securityContext:
  capabilities:
    drop:
    - ALL
  readOnlyRootFilesystem: false
  runAsNonRoot: true
  runAsUser: 10010

service:
  type: ClusterIP
  port: 80
  targetPort: 8080

ingress:
  enabled: true
  className: "nginx"
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /
    nginx.ingress.kubernetes.io/ssl-redirect: "false"
  hosts:
    - host: trunkline.local
      paths:
        - path: /
          pathType: Prefix
  tls: []

resources:
  limits:
    cpu: 1000m
    memory: 1Gi
  requests:
    cpu: 500m
    memory: 512Mi

autoscaling:
  # Not available today: HPA would raise the replica count, which is incompatible
  # with the H2 single-file database (issue #160)
  enabled: false
  minReplicas: 1
  maxReplicas: 1
  targetCPUUtilizationPercentage: 70
  targetMemoryUtilizationPercentage: 80

nodeSelector: {}

tolerations: []

affinity: {}

persistence:
  enabled: true
  storageClass: ""
  # Data volume must be ReadWriteOnce: H2 single-file database + replicas=1 (issue #160)
  accessMode: ReadWriteOnce
  size: 10Gi

config:
  application.yml: |
    server:
      port: 8080
    model:
      load-balance:
        type: round-robin
      rate-limit:
        enabled: true
        algorithm: token-bucket
        capacity: 1000
        rate: 100

monitoring:
  serviceMonitor:
    enabled: true
    interval: 30s
  prometheusRule:
    enabled: true
```

### 4. Template Files

Create `templates/deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "trunkline.fullname" . }}
  labels:
    {{- include "trunkline.labels" . | nindent 4 }}
spec:
  {{- if not .Values.autoscaling.enabled }}
  replicas: {{ .Values.replicaCount }}
  {{- end }}
  selector:
    matchLabels:
      {{- include "trunkline.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      {{- with .Values.podAnnotations }}
      annotations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      labels:
        {{- include "trunkline.selectorLabels" . | nindent 8 }}
    spec:
      {{- with .Values.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      serviceAccountName: {{ include "trunkline.serviceAccountName" . }}
      securityContext:
        {{- toYaml .Values.podSecurityContext | nindent 8 }}
      containers:
        - name: {{ .Chart.Name }}
          securityContext:
            {{- toYaml .Values.securityContext | nindent 12 }}
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag | default .Chart.AppVersion }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - name: http
              containerPort: 8080
              protocol: TCP
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: http
            initialDelaySeconds: 60
            periodSeconds: 30
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: http
            initialDelaySeconds: 30
            periodSeconds: 10
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
          volumeMounts:
            - name: config
              mountPath: /app/config
              readOnly: true
            - name: data
              mountPath: /app/data
            {{- if .Values.persistence.enabled }}
            - name: logs
              mountPath: /app/logs
            {{- end }}
      volumes:
        - name: config
          configMap:
            name: {{ include "trunkline.fullname" . }}-config
        - name: data
          persistentVolumeClaim:
            claimName: {{ include "trunkline.fullname" . }}-data
        {{- if .Values.persistence.enabled }}
        - name: logs
          persistentVolumeClaim:
            claimName: {{ include "trunkline.fullname" . }}-logs
        {{- end }}
      {{- with .Values.nodeSelector }}
      nodeSelector:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.affinity }}
      affinity:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.tolerations }}
      tolerations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
```

### 5. Deploy Helm Chart

```bash
# Install Chart
helm install trunkline ./trunkline-chart -n trunkline --create-namespace

# Upgrade Chart
helm upgrade trunkline ./trunkline-chart -n trunkline

# View status
helm status trunkline -n trunkline

# Uninstall Chart
helm uninstall trunkline -n trunkline
```

## Advanced Configuration

### 1. Pod Anti-affinity

```yaml
# Add to deployment.yaml
spec:
  template:
    spec:
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
          - weight: 100
            podAffinityTerm:
              labelSelector:
                matchExpressions:
                - key: app
                  operator: In
                  values:
                  - trunkline
              topologyKey: kubernetes.io/hostname
```

### 2. Pod Disruption Budget

```yaml
# pdb.yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: trunkline-pdb
  namespace: trunkline
spec:
  minAvailable: 2
  selector:
    matchLabels:
      app: trunkline
```

### 3. Network Policy

```yaml
# networkpolicy.yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: trunkline-netpol
  namespace: trunkline
spec:
  podSelector:
    matchLabels:
      app: trunkline
  policyTypes:
  - Ingress
  - Egress
  ingress:
  - from:
    - namespaceSelector:
        matchLabels:
          name: ingress-nginx
    ports:
    - protocol: TCP
      port: 8080
  egress:
  - to: []
    ports:
    - protocol: TCP
      port: 53
    - protocol: UDP
      port: 53
  - to: []
    ports:
    - protocol: TCP
      port: 80
    - protocol: TCP
      port: 443
```

## Operations Management

### 1. View Deployment Status

```bash
# View Pod status
kubectl get pods -n trunkline

# View Deployment status
kubectl get deployment -n trunkline

# View Service status
kubectl get service -n trunkline

# View Ingress status
kubectl get ingress -n trunkline

# View HPA status
kubectl get hpa -n trunkline
```

### 2. View Logs

```bash
# View Pod logs
kubectl logs -f deployment/trunkline -n trunkline

# View specific Pod logs
kubectl logs -f trunkline-xxx-yyy -n trunkline

# View all Pod logs
kubectl logs -f -l app=trunkline -n trunkline --max-log-requests=10
```

### 3. Rolling Updates

```bash
# Update image
kubectl set image deployment/jairouter jairouter=sodlinken/jairouter:v1.1.0 -n jairouter

# View update status
kubectl rollout status deployment/trunkline -n trunkline

# View update history
kubectl rollout history deployment/trunkline -n trunkline

# Rollback to previous version
kubectl rollout undo deployment/trunkline -n trunkline

# Rollback to specific version
kubectl rollout undo deployment/trunkline --to-revision=2 -n trunkline
```

### 4. Scaling

> **⚠️ Not available today**: `kubectl scale --replicas > 1` is incompatible with the H2 embedded single-file database and requires shared database adaptation first (issue #160). Rate limiting, quota, JWT blacklist, and API-Key caches are all per-Pod semantics — see "Multi-Replica Support Status (Known Constraints)" above.

```bash
# Manual scaling (not available today, see warning above)
# kubectl scale deployment trunkline --replicas=5 -n trunkline

# View HPA status
kubectl describe hpa trunkline-hpa -n trunkline

# Temporarily disable HPA
kubectl patch hpa trunkline-hpa -n trunkline -p '{"spec":{"minReplicas":0,"maxReplicas":0}}'
```

## Troubleshooting

### 1. Pod Troubleshooting

```bash
# View Pod details
kubectl describe pod trunkline-xxx-yyy -n trunkline

# View Pod events
kubectl get events --sort-by=.metadata.creationTimestamp -n trunkline

# Debug into Pod
kubectl exec -it trunkline-xxx-yyy -n trunkline -- sh

# View Pod resource usage
kubectl top pod -n trunkline
```

### 2. Network Troubleshooting

```bash
# Test Service connectivity
kubectl run test-pod --image=busybox -it --rm -- sh
# Execute inside Pod
nslookup trunkline-service.trunkline.svc.cluster.local
wget -qO- http://trunkline-service.trunkline.svc.cluster.local/actuator/health

# View Endpoints
kubectl get endpoints -n trunkline

# View Ingress status
kubectl describe ingress trunkline-ingress -n trunkline
```

### 3. Storage Troubleshooting

```bash
# View PVC status
kubectl get pvc -n trunkline

# View PV status
kubectl get pv

# View storage classes
kubectl get storageclass

# View Pod mount details
kubectl describe pod trunkline-xxx-yyy -n trunkline | grep -A 10 Volumes
```

## Best Practices

### 1. Resource Management

- Set appropriate resource requests and limits
- Use HPA and VPA for auto-scaling
- Configure Pod Disruption Budget
- Use anti-affinity to distribute Pods

### 2. Security Configuration

#### 1. Network Security Policy

Create `networkpolicy.yaml`:

```yaml
# Network policy configuration
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: trunkline-netpol
  namespace: trunkline
spec:
  podSelector:
    matchLabels:
      app: trunkline
  policyTypes:
  - Ingress
  - Egress
  ingress:
  # Allow traffic from Ingress Controller
  - from:
    - namespaceSelector:
        matchLabels:
          name: ingress-nginx
    ports:
    - protocol: TCP
      port: 8080
  # Allow traffic from monitoring components
  - from:
    - namespaceSelector:
        matchLabels:
          name: monitoring
    ports:
    - protocol: TCP
      port: 8080
  egress:
  # Allow DNS queries
  - to:
    - namespaceSelector:
        matchLabels:
          name: kube-system
    ports:
    - protocol: UDP
      port: 53
    - protocol: TCP
      port: 53
  # Allow access to external AI services
  - to: []
    ports:
    - protocol: TCP
      port: 443
    - protocol: TCP
      port: 80
```

#### 2. Pod Security Policy

Update `deployment.yaml`:

```yaml
# Deployment security configuration
apiVersion: apps/v1
kind: Deployment
metadata:
  name: trunkline
  namespace: trunkline
  labels:
    app: trunkline
spec:
  # Must be 1: the H2 embedded single-file database does not support multi-replica (issue #160)
  replicas: 1
  selector:
    matchLabels:
      app: trunkline
  template:
    metadata:
      labels:
        app: trunkline
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: "/actuator/prometheus"
    spec:
      # Security context configuration
      securityContext:
        runAsNonRoot: true
        runAsUser: 10010
        runAsGroup: 1001
        fsGroup: 10010
      containers:
      - name: trunkline
        image: sodlinken/jairouter:latest
        ports:
        - containerPort: 8080
          name: http
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "prod"
        - name: JAVA_OPTS
          value: "-Xms512m -Xmx1024m -XX:+UseG1GC -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
        - name: API_KEY
          valueFrom:
            secretKeyRef:
              name: trunkline-secret
              key: api-key
        - name: REDIS_HOST
          value: "your-redis-host"
        - name: REDIS_PORT
          value: "6379"
        - name: REDIS_PASSWORD
          valueFrom:
            secretKeyRef:
              name: trunkline-secret
              key: redis-password
        volumeMounts:
        - name: config-volume
          mountPath: /app/config
          readOnly: true
        - name: logs-volume
          mountPath: /app/logs
        - name: config-store-volume
          mountPath: /app/config-store
        - name: data-volume
          mountPath: /app/data
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
        # Container security context
        securityContext:
          allowPrivilegeEscalation: false
          readOnlyRootFilesystem: true
          capabilities:
            drop:
            - ALL
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 30
          timeoutSeconds: 10
          failureThreshold: 3
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
          timeoutSeconds: 5
          failureThreshold: 3
      volumes:
      - name: config-volume
        configMap:
          name: trunkline-config
      - name: logs-volume
        persistentVolumeClaim:
          claimName: trunkline-logs-pvc
      - name: config-store-volume
        persistentVolumeClaim:
          claimName: trunkline-config-pvc
      - name: data-volume
        persistentVolumeClaim:
          claimName: trunkline-data-pvc
      restartPolicy: Always
```

#### 3. Secret Management

Create `secret.yaml`:

```yaml
# Secret configuration
apiVersion: v1
kind: Secret
metadata:
  name: trunkline-secret
  namespace: trunkline
type: Opaque
data:
  # Base64 encoded keys
  api-key: eW91ci1hcGkta2V5LWhlcmU=  # your-api-key-here
  jwt-secret: eW91ci1qd3Qtc2VjcmV0LWtleQ==  # your-jwt-secret-key
  database-password: cGFzc3dvcmQ=     # password
  redis-password: cGFzc3dvcmQ=     # your-redis-password (base64)

---
# TLS Secret configuration
apiVersion: v1
kind: Secret
metadata:
  name: trunkline-tls
  namespace: trunkline
type: kubernetes.io/tls
data:
  tls.crt: LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCiMKLS0tLS1FTkQgQ0VSVElGSUNBVEUtLS0tLQo=
  tls.key: LS0tLS1CRUdJTiBSU0EgUFJJV
```

```bash
kubectl apply -f secret.yaml
```

#### 4. Application Security Configuration

Create `configmap-security.yaml`:

```yaml
# Security configuration ConfigMap
apiVersion: v1
kind: ConfigMap
metadata:
  name: trunkline-security-config
  namespace: trunkline
data:
  application-security.yml: |
    # Security configuration
    security:
      # API Key configuration
      api-key:
        enabled: true
        header: X-API-Key
        file: /app/config/api-keys.yml
      
      # JWT configuration
      jwt:
        enabled: true
        secret: ${JWT_SECRET}
        algorithm: HS256
        expiration-minutes: 60
        issuer: trunkline
        accounts:
          - username: admin
            password: ${ADMIN_PASSWORD}
            roles: [ADMIN, USER]
            enabled: true
          - username: user
            password: ${USER_PASSWORD}
            roles: [USER]
            enabled: true

      # CORS configuration
      cors:
        allowed-origins: "*"
        allowed-methods: "*"
        allowed-headers: "*"
        allow-credentials: false

    # HTTPS configuration
    server:
      port: 8443
      ssl:
        enabled: true
        key-store: /app/config/tls/keystore.p12
        key-store-password: ${SSL_KEYSTORE_PASSWORD}
        key-store-type: PKCS12
        key-alias: trunkline
```

Create `api-keys-config.yaml`:

```yaml
# API key configuration
apiVersion: v1
kind: ConfigMap
metadata:
  name: trunkline-api-keys
  namespace: trunkline
data:
  api-keys.yml: |
    # API key configuration
    api-keys:
      - name: "service-a"
        key: "sk-service-a-key-here"
        permissions:
          - "chat:read"
          - "embedding:read"
        enabled: true
      
      - name: "service-b"
        key: "sk-service-b-key-here"
        permissions:
          - "chat:*"
          - "embedding:*"
        enabled: true
```

### 3. Monitoring and Alerting

- Configure ServiceMonitor to collect metrics
- Set up PrometheusRule alert rules
- Use Grafana for monitoring visualization
- Configure alert notifications

### 4. High Availability

- Deploy multiple replicas
- Distribute across availability zones
- Configure health checks
- Implement rolling update strategies

## Next Steps

After completing the Kubernetes deployment, you can:

- **[Production Deployment](production.md)** - Configure a production-grade high-availability environment
- **[Monitoring Guide](../monitoring/index.md)** - Set up a complete monitoring system
- **[Troubleshooting](../troubleshooting/index.md)** - Learn K8s environment fault diagnosis
- **[Performance Tuning](../troubleshooting/performance.md)** - Optimize K8s deployment performance
