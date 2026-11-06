# Monitoring Metrics Reference

<!-- 版本信息 -->
> **Doc Version**: 1.0.2  
> **Last Updated**: 2026-05-21  
> **Git Commit**: 61384b4a  
> **Author**: Lincoln
<!-- /版本信息 -->



This document provides detailed descriptions of all available monitoring metrics in the Trunkline system, including metric definitions, label explanations, usage scenarios, and query examples.

> ⚠️ **Trust the code.** The generic HTTP/business/backend metric names below (e.g. `trunkline_requests_total`, `trunkline_model_calls_total`, `trunkline_backend_health`) are **design illustrations**. The metrics actually registered on the `MeterRegistry` and scrapable at `/actuator/prometheus` are the "Code-registered metrics" below; the rest are **to be confirmed** — trust the live `/actuator/prometheus` output.

## Code-registered metrics

### Security metrics (`monitor/monitoring/security/SecurityMetrics.java`)

Micrometer names (Prometheus counters get the `_total` suffix):

| Micrometer name | Type | Tags |
|-----------------|------|--------|
| `trunkline.security.authentication.attempts` | Counter | — |
| `trunkline.security.authentication.successes` | Counter | `auth_type` |
| `trunkline.security.authentication.failures` | Counter | `auth_type`, `failure_reason` |
| `trunkline.security.authentication.duration` | Timer | — |
| `trunkline.security.jwt.validations` | Counter | `result` |
| `trunkline.security.jwt.refreshes` / `.expired` | Counter | — |
| `trunkline.security.sanitization.operations` | Counter | `content_type`, `direction` |
| `trunkline.security.sanitization.rule.matches` | Counter | `rule_id`, `rule_type` |
| `trunkline.security.violations` | Counter | `violation_type`, `severity` |
| `trunkline.security.suspicious.activities` | Counter | `activity_type`, `client_ip_hash` |
| `trunkline.security.active.users` | Gauge | — |
| `trunkline.security.apikeys.active` | Gauge | — |

### Rate-limit metrics (exposed by `router/controller/RateLimitMonitorController`)

| Metric | Type | Tags |
|--------|------|--------|
| `trunkline_rate_limit_events_total` | Counter | `service`, `algorithm`, `result` |
| `trunkline_rate_limit_remaining` | Gauge | `service`, `scope`, `algorithm` |
| `trunkline_rate_limit_usage_ratio` | Gauge | `service`, `scope`, `algorithm` |

### Monitoring-system metrics (read by `DashboardMetricsController`)

`trunkline.metrics.cache.hits/misses(.total)`, `trunkline.metrics.cache.size`, `trunkline.metrics.retry.attempts/successes/failures(.total)`, `trunkline.metrics.retry.active`, `trunkline.metrics.degradation.level`, `trunkline.metrics.degradations.total`, `trunkline.metrics.errors.total`; audit `trunkline.audit.events.total`, `trunkline.audit.buffer.size`, `trunkline.audit.health.score`.

### JVM / HTTP generic metrics

Standard Spring Boot Actuator / Micrometer: `http.server.requests` (Timer), `jvm.memory.*`, `jvm.gc.*`, `jvm.threads.*`, `process.cpu.usage`, `process.uptime`.

## Metric Categories

Trunkline monitoring metrics are categorized by function into the following groups:

| Category | Prefix | Description | Usage |
|----------|--------|-------------|-------|
| System Metrics | `trunkline_system_*` | JVM, HTTP, and system resource related metrics | System health monitoring |
| Business Metrics | `trunkline_business_*` | AI model services and user request related metrics | Business analysis and optimization |
| Infrastructure Metrics | `trunkline_infrastructure_*` | Load balancing, rate limiting, and circuit breaker related metrics | Infrastructure monitoring |
| Request Metrics | `trunkline_requests_*` | HTTP request statistics | Performance and availability monitoring |
| Backend Call Metrics | `trunkline_backend_*` | Backend adapter call metrics | Backend service monitoring |

## HTTP Request Metrics

### trunkline_requests_total
**Type**: Counter  
**Description**: Total count of HTTP requests  
**Unit**: Count

**Labels**:
- `service`: Service type (chat, embedding, rerank, tts, stt, image)
- `method`: HTTP method (GET, POST, PUT, DELETE)
- `status`: HTTP status code (200, 400, 404, 500, etc.)
- `path`: Request path
- `client_type`: Client type (web, mobile, api)

**Usage Scenarios**:
- Calculate request rate and throughput
- Analyze service usage patterns
- Monitor error rates

**Query Examples**:
```promql
# Total request rate (RPS)
sum(rate(trunkline_requests_total[5m]))

# Request rate grouped by service type
sum by (service) (rate(trunkline_requests_total[5m]))

# Error rate calculation
sum(rate(trunkline_requests_total{status=~"4..|5.."}[5m])) / sum(rate(trunkline_requests_total[5m])) * 100

# Success rate calculation
sum(rate(trunkline_requests_total{status=~"2.."}[5m])) / sum(rate(trunkline_requests_total[5m])) * 100
```

### trunkline_request_duration_seconds
**Type**: Histogram  
**Description**: Distribution of HTTP request response times  
**Unit**: Seconds

**Labels**:
- `service`: Service type
- `method`: HTTP method
- `status`: HTTP status code

**Buckets**: 0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, +Inf

**Usage Scenarios**:
- Monitor response time performance
- Set SLA alerts
- Performance trend analysis

**Query Examples**:
```promql
# P95 response time
histogram_quantile(0.95, sum(rate(trunkline_request_duration_seconds_bucket[5m])) by (le))

# P99 response time grouped by service
histogram_quantile(0.99, sum by (service, le) (rate(trunkline_request_duration_seconds_bucket[5m])))

# Average response time
sum(rate(trunkline_request_duration_seconds_sum[5m])) / sum(rate(trunkline_request_duration_seconds_count[5m]))

# Proportion of requests exceeding 2 seconds
sum(rate(trunkline_request_duration_seconds_bucket{le="2"}[5m])) / sum(rate(trunkline_request_duration_seconds_count[5m]))
```

### trunkline_request_size_bytes
**Type**: Histogram  
**Description**: Distribution of HTTP request sizes  
**Unit**: Bytes

**Labels**:
- `service`: Service type
- `method`: HTTP method

**Buckets**: 100, 1000, 10000, 100000, 1000000, 10000000, +Inf

**Query Examples**:
```promql
# P95 request size
histogram_quantile(0.95, sum(rate(trunkline_request_size_bytes_bucket[5m])) by (le))

# Average request size
sum(rate(trunkline_request_size_bytes_sum[5m])) / sum(rate(trunkline_request_size_bytes_count[5m]))

# Proportion of large requests (>1MB)
sum(rate(trunkline_request_size_bytes_bucket{le="1000000"}[5m])) / sum(rate(trunkline_request_size_bytes_count[5m]))
```

### trunkline_response_size_bytes
**Type**: Histogram  
**Description**: Distribution of HTTP response sizes  
**Unit**: Bytes

**Labels**:
- `service`: Service type
- `status`: HTTP status code

**Query Examples**:
```promql
# P95 response size
histogram_quantile(0.95, sum(rate(trunkline_response_size_bytes_bucket[5m])) by (le))

# Average response size grouped by service type
sum by (service) (rate(trunkline_response_size_bytes_sum[5m])) / sum by (service) (rate(trunkline_response_size_bytes_count[5m]))
```

## Business Metrics

### trunkline_model_calls_total
**Type**: Counter  
**Description**: Total count of AI model calls  
**Unit**: Count

**Labels**:
- `model_type`: Model type (chat, embedding, rerank)
- `model_name`: Specific model name
- `provider`: Model provider
- `status`: Call status (success, error, timeout)

**Usage Scenarios**:
- Monitor model usage
- Analyze model performance
- Calculate success rate

**Query Examples**:
```promql
# Model call rate
sum(rate(trunkline_model_calls_total[5m]))

# Call rate grouped by model type
sum by (model_type) (rate(trunkline_model_calls_total[5m]))

# Model call success rate
sum(rate(trunkline_model_calls_total{status="success"}[5m])) / sum(rate(trunkline_model_calls_total[5m])) * 100

# Most popular models
topk(5, sum by (model_name) (rate(trunkline_model_calls_total[5m])))
```

### trunkline_model_call_duration_seconds
**Type**: Histogram  
**Description**: Distribution of model call response times  
**Unit**: Seconds

**Labels**:
- `model_type`: Model type
- `model_name`: Model name
- `provider`: Model provider

**Buckets**: 0.1, 0.5, 1, 2, 5, 10, 30, 60, 120, +Inf

**Query Examples**:
```promql
# P95 model call response time
histogram_quantile(0.95, sum(rate(trunkline_model_call_duration_seconds_bucket[5m])) by (le))

# Average response time grouped by model type
sum by (model_type) (rate(trunkline_model_call_duration_seconds_sum[5m])) / sum by (model_type) (rate(trunkline_model_call_duration_seconds_count[5m]))

# Fastest responding models
bottomk(5, sum by (model_name) (rate(trunkline_model_call_duration_seconds_sum[5m])) / sum by (model_name) (rate(trunkline_model_call_duration_seconds_count[5m])))
```

### trunkline_user_sessions_active
**Type**: Gauge  
**Description**: Current number of active user sessions  
**Unit**: Count

**Labels**:
- `service`: Service type
- `region`: Geographic region

**Query Examples**:
```promql
# Total active sessions
sum(trunkline_user_sessions_active)

# Active sessions grouped by region
sum by (region) (trunkline_user_sessions_active)

# Session growth rate
rate(trunkline_user_sessions_active[5m])
```

### trunkline_user_sessions_total
**Type**: Counter  
**Description**: Total number of user sessions  
**Unit**: Count

**Labels**:
- `service`: Service type
- `session_type`: Session type (new, returning)

**Query Examples**:
```promql
# New session creation rate
sum(rate(trunkline_user_sessions_total{session_type="new"}[5m]))

# Returning user ratio
sum(rate(trunkline_user_sessions_total{session_type="returning"}[5m])) / sum(rate(trunkline_user_sessions_total[5m])) * 100
```

## Infrastructure Metrics

### Load Balancer Metrics

#### trunkline_loadbalancer_selections_total
**Type**: Counter  
**Description**: Total number of times the load balancer selects backend instances  
**Unit**: Count

**Labels**:
- `service`: Service name
- `strategy`: Load balancing strategy (random, round_robin, least_connections, ip_hash)
- `selected_instance`: Selected instance

**Query Examples**:
```promql
# Load balancer selection rate
sum(rate(trunkline_loadbalancer_selections_total[5m]))

# Selection distribution grouped by strategy
sum by (strategy) (rate(trunkline_loadbalancer_selections_total[5m]))

# Instance selection uniformity
stddev by (service) (sum by (service, selected_instance) (rate(trunkline_loadbalancer_selections_total[5m])))

# Most frequently selected instances
topk(5, sum by (selected_instance) (rate(trunkline_loadbalancer_selections_total[5m])))
```

### Rate Limiter Metrics

#### trunkline_rate_limit_events_total
**Type**: Counter  
**Description**: Total number of rate limiting events  
**Unit**: Count

**Labels**:
- `service`: Service name
- `algorithm`: Rate limiting algorithm (token_bucket, leaky_bucket, sliding_window)
- `result`: Rate limiting result (allowed, denied)

**Query Examples**:
```promql
# Rate limit pass rate
sum(rate(trunkline_rate_limit_events_total{result="allowed"}[5m])) / sum(rate(trunkline_rate_limit_events_total[5m])) * 100

# Rate limit rejection rate
sum(rate(trunkline_rate_limit_events_total{result="denied"}[5m])) / sum(rate(trunkline_rate_limit_events_total[5m])) * 100

# Rate limiting effectiveness grouped by algorithm
sum by (algorithm) (rate(trunkline_rate_limit_events_total{result="denied"}[5m]))
```

#### trunkline_rate_limit_remaining
**Type**: Gauge  
**Description**: Remaining quota in the rate limiter (by service/scope/algorithm)  
**Unit**: Count

**Labels**:
- `service`: Service name
- `scope`: Rate-limit scope
- `algorithm`: Rate limiting algorithm

**Query Examples**:
```promql
# Remaining quota for a service
trunkline_rate_limit_remaining{service="chat"}

# Service with the least remaining quota
min by (service) (trunkline_rate_limit_remaining)
```

#### trunkline_rate_limit_usage_ratio
**Type**: Gauge  
**Description**: Rate-limit quota usage ratio (0–1)  
**Unit**: Ratio

**Labels**:
- `service`: Service name
- `scope`: Rate-limit scope
- `algorithm`: Rate limiting algorithm

**Query Examples**:
```promql
# Services over 90% used
trunkline_rate_limit_usage_ratio > 0.9
```

> Note: the earlier `trunkline_rate_limit_tokens` (and `_max`) is **not** registered in the current code; use `remaining` and `usage_ratio` above.

### Circuit Breaker Metrics

#### trunkline_circuit_breaker_state
**Type**: Gauge  
**Description**: Current state of the circuit breaker  
**Unit**: State value

**Labels**:
- `service`: Service name
- `circuit_breaker`: Circuit breaker name

**Value Definition**:
- 0: CLOSED (normal state)
- 1: OPEN (circuit breaker open)
- 2: HALF_OPEN (half-open state)

**Query Examples**:
```promql
# Circuit breaker state
trunkline_circuit_breaker_state

# Number of services with open circuit breakers
count(trunkline_circuit_breaker_state == 1)

# Circuit breakers in half-open state
trunkline_circuit_breaker_state == 2
```

#### trunkline_circuit_breaker_events_total
**Type**: Counter  
**Description**: Total number of circuit breaker events  
**Unit**: Count

**Labels**:
- `service`: Service name
- `circuit_breaker`: Circuit breaker name
- `event`: Event type (success, error, timeout, circuit_open, circuit_close)

**Query Examples**:
```promql
# Circuit breaker success rate
sum(rate(trunkline_circuit_breaker_events_total{event="success"}[5m])) / sum(rate(trunkline_circuit_breaker_events_total{event=~"success|error"}[5m])) * 100

# Circuit breaker opening frequency
sum(rate(trunkline_circuit_breaker_events_total{event="circuit_open"}[5m]))

# Circuit breaker recovery frequency
sum(rate(trunkline_circuit_breaker_events_total{event="circuit_close"}[5m]))
```

#### trunkline_circuit_breaker_failure_rate
**Type**: Gauge  
**Description**: Current failure rate of the circuit breaker  
**Unit**: Percentage

**Labels**:
- `service`: Service name
- `circuit_breaker`: Circuit breaker name

**Query Examples**:
```promql
# Failure rate
trunkline_circuit_breaker_failure_rate

# Services with failure rate exceeding threshold
trunkline_circuit_breaker_failure_rate > 0.5

# Failure rate trend
rate(trunkline_circuit_breaker_failure_rate[5m])
```

## Backend Service Metrics

### trunkline_backend_health
**Type**: Gauge  
**Description**: Health status of backend services  
**Unit**: Status value

**Labels**:
- `adapter`: Adapter type (gpustack, ollama, vllm)
- `instance`: Instance name
- `base_url`: Backend service URL

**Value Definition**:
- 1: Healthy
- 0: Unhealthy

**Query Examples**:
```promql
# Number of healthy backend instances
sum(trunkline_backend_health)

# Unhealthy backend instances
trunkline_backend_health == 0

# Number of healthy instances grouped by adapter
sum by (adapter) (trunkline_backend_health)

# Health rate
sum(trunkline_backend_health) / count(trunkline_backend_health) * 100
```

### trunkline_backend_calls_total
**Type**: Counter  
**Description**: Total number of backend adapter calls  
**Unit**: Count

**Labels**:
- `adapter`: Adapter type (gpustack, ollama, vllm, xinference, localai, openai)
- `instance`: Instance name
- `method`: Call method
- `status`: Call status (success, error, timeout)

**Query Examples**:
```promql
# Backend call rate
sum(rate(trunkline_backend_calls_total[5m]))

# Call rate grouped by adapter
sum by (adapter) (rate(trunkline_backend_calls_total[5m]))

# Backend call success rate
sum(rate(trunkline_backend_calls_total{status="success"}[5m])) / sum(rate(trunkline_backend_calls_total[5m])) * 100

# Busiest backend instances
topk(5, sum by (instance) (rate(trunkline_backend_calls_total[5m])))
```

### trunkline_backend_call_duration_seconds
**Type**: Histogram  
**Description**: Distribution of backend call response times  
**Unit**: Seconds

**Labels**:
- `adapter`: Adapter type
- `instance`: Instance name
- `method`: Call method

**Buckets**: 0.1, 0.5, 1, 2, 5, 10, 30, 60, 120, +Inf

**Query Examples**:
```promql
# P95 backend call response time
histogram_quantile(0.95, sum(rate(trunkline_backend_call_duration_seconds_bucket[5m])) by (le))

# Average response time grouped by adapter
sum by (adapter) (rate(trunkline_backend_call_duration_seconds_sum[5m])) / sum by (adapter) (rate(trunkline_backend_call_duration_seconds_count[5m]))

# Fastest responding adapters
bottomk(3, sum by (adapter) (rate(trunkline_backend_call_duration_seconds_sum[5m])) / sum by (adapter) (rate(trunkline_backend_call_duration_seconds_count[5m])))
```

## JVM Metrics

### Memory Metrics

#### jvm_memory_used_bytes
**Type**: Gauge  
**Description**: JVM memory usage  
**Unit**: Bytes

**Labels**:
- `area`: Memory area (heap, nonheap)
- `id`: Memory pool ID

**Query Examples**:
```promql
# Heap memory usage
jvm_memory_used_bytes{area="heap"}

# Memory utilization rate
jvm_memory_used_bytes / jvm_memory_max_bytes * 100

# Memory usage trend
rate(jvm_memory_used_bytes[5m])
```

#### jvm_memory_max_bytes
**Type**: Gauge  
**Description**: Maximum JVM memory capacity  
**Unit**: Bytes

### Garbage Collection Metrics

#### jvm_gc_pause_seconds
**Type**: Timer  
**Description**: GC pause time  
**Unit**: Seconds

**Labels**:
- `action`: GC action
- `cause`: GC cause

**Query Examples**:
```promql
# Average GC pause time
sum(rate(jvm_gc_pause_seconds_sum[5m])) / sum(rate(jvm_gc_pause_seconds_count[5m]))

# GC frequency
sum(rate(jvm_gc_pause_seconds_count[5m]))

# GC time proportion
sum(rate(jvm_gc_pause_seconds_sum[5m])) / 60 * 100
```

### Thread Metrics

#### jvm_threads_live_threads
**Type**: Gauge  
**Description**: Current number of live threads  
**Unit**: Count

**Query Examples**:
```promql
# Live thread count
jvm_threads_live_threads

# Thread count trend
rate(jvm_threads_live_threads[5m])
```

## Connection Pool Metrics

### trunkline_backend_connection_pool_active
**Type**: Gauge  
**Description**: Number of active connections in backend connection pool  
**Unit**: Count

**Labels**:
- `adapter`: Adapter type
- `instance`: Instance name

**Query Examples**:
```promql
# Connection pool usage
trunkline_backend_connection_pool_active

# Connection pool utilization rate
trunkline_backend_connection_pool_active / trunkline_backend_connection_pool_max * 100

# Instances with exhausted connection pools
trunkline_backend_connection_pool_active >= trunkline_backend_connection_pool_max
```

## Custom Metrics

### Business-Specific Metrics

Trunkline supports custom business metrics, which can be added as follows:

```java
@Component
public class CustomMetrics {
    private final Counter customCounter = Counter.builder("trunkline.custom.counter")
        .description("Custom counter")
        .tag("type", "business")
        .register(Metrics.globalRegistry);
    
    private final Gauge customGauge = Gauge.builder("trunkline.custom.gauge")
        .description("Custom gauge")
        .register(Metrics.globalRegistry, this, CustomMetrics::getValue);
    
    private final Timer customTimer = Timer.builder("trunkline.custom.timer")
        .description("Custom timer")
        .register(Metrics.globalRegistry);
}
```

## Common Query Patterns

### Error Rate Calculation

```promql
# HTTP error rate
sum(rate(trunkline_requests_total{status=~"4..|5.."}[5m])) / sum(rate(trunkline_requests_total[5m])) * 100

# Backend call error rate
sum(rate(trunkline_backend_calls_total{status="error"}[5m])) / sum(rate(trunkline_backend_calls_total[5m])) * 100

# Model call error rate
sum(rate(trunkline_model_calls_total{status!="success"}[5m])) / sum(rate(trunkline_model_calls_total[5m])) * 100
```

### Response Time Analysis

```promql
# P50, P95, P99 response times
histogram_quantile(0.50, sum(rate(trunkline_request_duration_seconds_bucket[5m])) by (le))
histogram_quantile(0.95, sum(rate(trunkline_request_duration_seconds_bucket[5m])) by (le))
histogram_quantile(0.99, sum(rate(trunkline_request_duration_seconds_bucket[5m])) by (le))

# Response times grouped by service
histogram_quantile(0.95, sum by (service, le) (rate(trunkline_request_duration_seconds_bucket[5m])))
```

### Throughput Analysis

```promql
# Total throughput (RPS)
sum(rate(trunkline_requests_total[5m]))

# Throughput grouped by service
sum by (service) (rate(trunkline_requests_total[5m]))

# Successful request throughput
sum(rate(trunkline_requests_total{status=~"2.."}[5m]))
```

### Resource Usage Analysis

```promql
# CPU utilization rate
rate(process_cpu_seconds_total[5m]) * 100

# Memory utilization rate
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} * 100

# Connection pool utilization rate
trunkline_backend_connection_pool_active / trunkline_backend_connection_pool_max * 100
```

### Service Health Status

```promql
# Service availability
up{job="trunkline"}

# Backend service health status
sum(trunkline_backend_health) / count(trunkline_backend_health) * 100

# Services with open circuit breakers
count(trunkline_circuit_breaker_state == 1)
```

## Alert Queries

### Critical Alerts

```promql
# Service unavailable
up{job="trunkline"} == 0

# High error rate
sum(rate(trunkline_requests_total{status=~"5.."}[5m])) / sum(rate(trunkline_requests_total[5m])) > 0.05

# Long response time
histogram_quantile(0.95, sum(rate(trunkline_request_duration_seconds_bucket[5m])) by (le)) > 5
```

### Warning Alerts

```promql
# High memory usage
jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 0.8

# High connection pool usage
trunkline_backend_connection_pool_active / trunkline_backend_connection_pool_max > 0.8

# Long GC time
sum(rate(jvm_gc_pause_seconds_sum[5m])) / sum(rate(jvm_gc_pause_seconds_count[5m])) > 0.1
```

## Capacity Planning Queries

### Growth Trends

```promql
# Request volume growth trend
increase(trunkline_requests_total[24h])

# User growth trend
increase(trunkline_user_sessions_total[24h])

# Resource usage growth trend
increase(jvm_memory_used_bytes[24h])
```

### Peak Analysis

```promql
# Daily peak request rate
max_over_time(sum(rate(trunkline_requests_total[5m]))[24h:5m])

# Weekly peak response time
max_over_time(histogram_quantile(0.95, sum(rate(trunkline_request_duration_seconds_bucket[5m])) by (le))[7d:1h])
```

## Metric Label Best Practices

### Label Design Principles

1. **Low Cardinality**: Avoid high cardinality labels like user IDs or IP addresses
2. **Meaningful**: Labels should help with problem identification and analysis
3. **Consistent**: Labels with the same meaning should use the same name across different metrics
4. **Hierarchical**: Use hierarchical label structures to facilitate aggregation queries

### Common Labels

- `service`: Service type
- `method`: HTTP method or call method
- `status`: Status code or call result
- `instance`: Instance identifier
- `environment`: Environment identifier (dev, test, prod)
- [version](file://springfox\documentation\service\ApiInfo.java#L8-L8): Version number

### Label Usage Examples

```promql
# Good query - using low cardinality labels
sum by (service, status) (rate(trunkline_requests_total[5m]))

# Avoided query - using high cardinality labels
sum by (client_ip) (rate(trunkline_requests_total[5m]))  # May cause high cardinality issues
```

## Recording Rules

To improve query performance, it is recommended to use Recording Rules to pre-aggregate common metrics:

```yaml
# rules/trunkline-recording-rules.yml
groups:
  - name: trunkline.recording
    interval: 30s
    rules:
      - record: trunkline:request_rate_5m
        expr: sum(rate(trunkline_requests_total[5m]))
      
      - record: trunkline:error_rate_5m
        expr: sum(rate(trunkline_requests_total{status=~"4..|5.."}[5m])) / sum(rate(trunkline_requests_total[5m]))
      
      - record: trunkline:response_time_p95_5m
        expr: histogram_quantile(0.95, sum(rate(trunkline_request_duration_seconds_bucket[5m])) by (le))
      
      - record: trunkline:backend_health_ratio
        expr: sum(trunkline_backend_health) / count(trunkline_backend_health)
```

## Related Documentation

- [Monitoring Setup Guide](setup.md)
- [Grafana Dashboard Usage Guide](dashboards.md)
- [Alert Configuration Guide](alerts.md)
- [Troubleshooting Guide](troubleshooting.md)

---

**Tip**: Regularly review and optimize metric usage, remove metrics that are no longer needed, and avoid metric inflation that could impact system performance.
