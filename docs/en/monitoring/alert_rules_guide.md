# Trunkline Alert Rules Guide

<!-- Version Information -->
> **Version**: 1.1.1
> **Last Updated**: 2026-09-15
> **Applicable Version**: v3.1.1x+
> **Git Commit**: 135f9a60
> **Author**: Lincoln
<!-- /Version Information -->

## Overview

This document details the Prometheus alert rules configuration for the Trunkline project, including alert types, trigger conditions, and handling recommendations.

## Alert Rule Categories

### 1. Basic Service Alerts (trunkline.basic)

#### TrunklineServiceDown
- **Description**: Trunkline service unavailable
- **Trigger Condition**: `up{job="trunkline"} == 0`
- **Duration**: 1 minute
- **Severity**: Critical
- **Handling Recommendations**:
  1. Check Trunkline service process status
  2. Review application startup logs
  3. Verify port usage
  4. Check system resources availability

#### TrunklineHighErrorRate
- **Description**: Trunkline error rate too high
- **Trigger Condition**: 4xx/5xx error rate exceeds 10%
- **Duration**: 2 minutes
- **Severity**: Warning
- **Handling Recommendations**:
  1. Check application error logs
  2. Verify backend service status
  3. Check network connectivity
  4. Analyze error type distribution

#### TrunklineCriticalErrorRate
- **Description**: Trunkline critical error rate too high
- **Trigger Condition**: 5xx error rate exceeds 5%
- **Duration**: 1 minute
- **Severity**: Critical
- **Handling Recommendations**:
  1. Immediately check server status
  2. Review application exception logs
  3. Check database connections
  4. Verify dependency service availability

### 2. Performance Alerts (trunkline.performance)

#### TrunklineHighLatency
- **Description**: Trunkline response time too high
- **Trigger Condition**: 95th percentile response time exceeds 2 seconds
- **Duration**: 3 minutes
- **Severity**: Warning
- **Handling Recommendations**:
  1. Check system resource usage
  2. Analyze slow queries and performance bottlenecks
  3. Verify backend service response times
  4. Check network latency

#### TrunklineCriticalLatency
- **Description**: Trunkline response time critically high
- **Trigger Condition**: 95th percentile response time exceeds 5 seconds
- **Duration**: 1 minute
- **Severity**: Critical
- **Handling Recommendations**:
  1. Immediately check system load
  2. Analyze performance bottlenecks
  3. Consider temporary rate limiting
  4. Check if scaling is needed

#### TrunklineLowRequestVolume
- **Description**: Trunkline request volume abnormally low
- **Trigger Condition**: Request rate below 0.1 req/s
- **Duration**: 5 minutes
- **Severity**: Warning
- **Handling Recommendations**:
  1. Check client connection status
  2. Verify load balancer configuration
  3. Check network routing
  4. Confirm if it's normal business low period

#### TrunklineSlowQueriesDetected
- **Description**: Trunkline detected slow queries
- **Trigger Condition**: More than 5 slow queries in 5 minutes
- **Duration**: 1 minute
- **Severity**: Warning
- **Handling Recommendations**:
  1. Check slow query logs
  2. Analyze slow query causes
  3. Optimize related queries or operations
  4. Consider adding indexes or caching

#### TrunklineHighSlowQueryRate
- **Description**: Trunkline slow query rate too high
- **Trigger Condition**: Slow query rate exceeds 1/second
- **Duration**: 2 minutes
- **Severity**: Critical
- **Handling Recommendations**:
  1. Immediately analyze system performance bottlenecks
  2. Check database connections and queries
  3. Evaluate if resource scaling is needed
  4. Consider temporary rate limiting measures

### 3. Backend Service Alerts (trunkline.backend)

#### TrunklineBackendDown
- **Description**: Trunkline backend service unavailable
- **Trigger Condition**: `trunkline_backend_health == 0`
- **Duration**: 1 minute
- **Severity**: Critical
- **Handling Recommendations**:
  1. Check backend service status
  2. Verify network connectivity
  3. Check service configuration
  4. Review health check logs

#### TrunklineBackendHighLatency
- **Description**: Trunkline backend service responding slowly
- **Trigger Condition**: Backend 95th percentile response time exceeds 3 seconds
- **Duration**: 2 minutes
- **Severity**: Warning
- **Handling Recommendations**:
  1. Check backend service performance
  2. Analyze network latency
  3. Verify backend resource usage
  4. Consider adjusting timeout configuration

#### TrunklineBackendHighErrorRate
- **Description**: Trunkline backend service error rate high
- **Trigger Condition**: Backend error rate exceeds 15%
- **Duration**: 2 minutes
- **Severity**: Warning
- **Handling Recommendations**:
  1. Check backend service logs
  2. Verify API compatibility
  3. Check authentication configuration
  4. Analyze error types

### 4. Infrastructure Alerts (trunkline.infrastructure)

#### TrunklineCircuitBreakerOpen
- **Description**: Trunkline circuit breaker opened
- **Trigger Condition**: `trunkline_circuit_breaker_state == 1`
- **Duration**: 30 seconds
- **Severity**: Warning
- **Handling Recommendations**:
  1. Check downstream service status
  2. Analyze failure rate causes
  3. Verify circuit breaker configuration
  4. Consider manual recovery

#### TrunklineRateLimitTriggered
- **Description**: Trunkline rate limiter frequently triggered
- **Trigger Condition**: Rate limit rejection rate exceeds 10 req/s
- **Duration**: 1 minute
- **Severity**: Warning
- **Handling Recommendations**:
  1. Analyze request sources
  2. Check rate limit configuration
  3. Evaluate if threshold adjustment needed
  4. Consider capacity increase

#### TrunklineLoadBalancerImbalance
- **Description**: Trunkline load balancing uneven
- **Trigger Condition**: Instance request volume difference exceeds 50%
- **Duration**: 5 minutes
- **Severity**: Warning
- **Handling Recommendations**:
  1. Check load balancing strategy
  2. Verify instance health status
  3. Analyze instance performance differences
  4. Consider adjusting weight configuration

### 5. Resource Alerts (trunkline.resources)

#### TrunklineHighMemoryUsage
- **Description**: Trunkline memory usage high
- **Trigger Condition**: JVM heap memory usage exceeds 80%
- **Duration**: 2 minutes
- **Severity**: Warning
- **Handling Recommendations**:
  1. Check memory leaks
  2. Analyze GC logs
  3. Consider adjusting JVM parameters
  4. Evaluate if scaling needed

#### TrunklineCriticalMemoryUsage
- **Description**: Trunkline memory usage critically high
- **Trigger Condition**: JVM heap memory usage exceeds 90%
- **Duration**: 1 minute
- **Severity**: Critical
- **Handling Recommendations**:
  1. Immediately check memory usage
  2. Consider restarting service
  3. Increase memory configuration
  4. Analyze memory leak causes

#### TrunklineHighGCRate
- **Description**: Trunkline GC frequency too high
- **Trigger Condition**: GC frequency exceeds 0.2/second
- **Duration**: 3 minutes
- **Severity**: Warning
- **Handling Recommendations**:
  1. Analyze GC logs
  2. Optimize JVM parameters
  3. Check memory allocation patterns
  4. Consider adjusting heap size

#### TrunklineHighThreadCount
- **Description**: Trunkline thread count too high
- **Trigger Condition**: Current thread count exceeds 200
- **Duration**: 2 minutes
- **Severity**: Warning
- **Handling Recommendations**:
  1. Check thread pool configuration
  2. Analyze thread stacks
  3. Find thread leaks
  4. Optimize concurrent handling

### 6. Business Metric Alerts (trunkline.business)

#### TrunklineModelCallFailureRate
- **Description**: Trunkline model call failure rate high
- **Trigger Condition**: Model call failure rate exceeds 20%
- **Duration**: 2 minutes
- **Severity**: Warning
- **Handling Recommendations**:
  1. Check AI model service status
  2. Verify API keys and configuration
  3. Analyze failure causes
  4. Check network connectivity

#### TrunklineLargeRequestSize
- **Description**: Trunkline request size abnormal
- **Trigger Condition**: 95th percentile request size exceeds 1MB
- **Duration**: 3 minutes
- **Severity**: Warning
- **Handling Recommendations**:
  1. Analyze request content
  2. Check client behavior
  3. Consider adding size limits
  4. Optimize data transfer

#### TrunklineLargeResponseSize
- **Description**: Trunkline response size abnormal
- **Trigger Condition**: 95th percentile response size exceeds 5MB
- **Duration**: 3 minutes
- **Severity**: Warning
- **Handling Recommendations**:
  1. Check response content
  2. Optimize data format
  3. Consider pagination handling
  4. Check for data leaks

### 7. Security Alerts (trunkline.security)

#### TrunklineSuspiciousIPActivity
- **Description**: Trunkline detected suspicious IP activity
- **Trigger Condition**: Single IP request rate exceeds 100 req/s
- **Duration**: 2 minutes
- **Severity**: Warning
- **Handling Recommendations**:
  1. Analyze IP access patterns
  2. Check if it's attack behavior
  3. Consider temporary blocking
  4. Strengthen access controls

#### TrunklineHighAuthFailureRate
- **Description**: Trunkline authentication failure rate high
- **Trigger Condition**: 401 error rate exceeds 5%
- **Duration**: 2 minutes
- **Severity**: Warning
- **Handling Recommendations**:
  1. Check authentication system status
  2. Analyze failure causes
  3. Verify key configuration
  4. Check for brute force attempts

#### TrunklineHighClientErrorRate
- **Description**: Trunkline client error rate high
- **Trigger Condition**: 4xx error rate exceeds 20%
- **Duration**: 3 minutes
- **Severity**: Warning
- **Handling Recommendations**:
  1. Analyze client requests
  2. Check API documentation consistency
  3. Verify parameter validation logic
  4. Provide better error information

### 8. Capacity Planning Alerts (trunkline.capacity)

#### TrunklineRequestVolumeGrowth
- **Description**: Trunkline request volume significant growth
- **Trigger Condition**: Growth exceeds 50% compared to 24 hours ago
- **Duration**: 5 minutes
- **Severity**: Info
- **Handling Recommendations**:
  1. Analyze growth causes
  2. Evaluate system capacity
  3. Consider scaling plans
  4. Monitor resource usage

#### TrunklineLowDiskSpace
- **Description**: Trunkline server disk space insufficient
- **Trigger Condition**: Available disk space below 20%
- **Duration**: 5 minutes
- **Severity**: Warning
- **Handling Recommendations**:
  1. Clean temporary files
  2. Archive historical logs
  3. Check disk usage
  4. Consider expansion

#### TrunklineHighCPUUsage
- **Description**: Trunkline server CPU usage high
- **Trigger Condition**: CPU usage exceeds 80%
- **Duration**: 3 minutes
- **Severity**: Warning
- **Handling Recommendations**:
  1. Analyze CPU usage
  2. Check process status
  3. Optimize performance bottlenecks
  4. Consider scaling

### 9. Dependency Service Alerts (trunkline.dependencies)

#### TrunklineDatabaseConnectionIssue
- **Description**: Trunkline database connection pool usage high
- **Trigger Condition**: Connection pool usage exceeds 80%
- **Duration**: 2 minutes
- **Severity**: Warning
- **Handling Recommendations**:
  1. Check database status
  2. Analyze connection leaks
  3. Optimize connection pool configuration
  4. Check slow queries

#### TrunklineLowCacheHitRate
- **Description**: Trunkline cache hit rate low
- **Trigger Condition**: Cache hit rate below 70%
- **Duration**: 5 minutes
- **Severity**: Warning
- **Handling Recommendations**:
  1. Analyze caching strategy
  2. Check cache configuration
  3. Optimize cache key design
  4. Consider cache warming

#### TrunklineExternalAPITimeout
- **Description**: Trunkline external API call timeout frequent
- **Trigger Condition**: Timeout frequency exceeds 5/second
- **Duration**: 2 minutes
- **Severity**: Warning
- **Handling Recommendations**:
  1. Check external service status
  2. Analyze network latency
  3. Adjust timeout configuration
  4. Consider retry strategy

## Alert Handling Process

### 1. Alert Reception
- Receive alert notifications via email, Slack, DingTalk, etc.
- View alert details and severity level
- Confirm alert authenticity and urgency

### 2. Initial Diagnosis
- Access Grafana dashboards for detailed metrics
- Check Prometheus alert page for related alerts
- Review application and system logs

### 3. Problem Handling
- Execute corresponding handling steps based on alert type
- Record handling process and results
- Contact relevant teams if necessary

### 4. Recovery Verification
- Confirm problem resolved
- Verify related metrics return to normal
- Wait for alert auto-resolution

### 5. Post-analysis
- Analyze problem root cause
- Evaluate if alert rule adjustments needed
- Improve preventive measures and handling procedures

## Alert Rule Maintenance

### Regular Checks
- Monthly check of alert rule effectiveness
- Adjust thresholds based on business changes
- Clean outdated or invalid alert rules

### Threshold Tuning
- Analyze reasonable thresholds based on historical data
- Avoid excessive false positives and negatives
- Consider business characteristics and user experience

### Documentation Updates
- Timely update alert handling documentation
- Record common issues and solutions
- Share best practices and lessons learned

## Testing and Validation

### Syntax Check
```bash
# Linux/macOS
./monitoring/prometheus/test-alerts.sh

# Windows
.\monitoring\prometheus\test-alerts.ps1
```

### Complete Validation
```bash
# Linux/macOS
./monitoring/prometheus/validate-alerts.sh

# Windows
.\monitoring\prometheus\validate-alerts.ps1
```

### Manual Testing
- Simulate failure scenarios to trigger alerts
- Verify notification channels are working
- Test alert recovery mechanism

## Related Links

- [Prometheus Alert Rules Documentation](https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/)
- [AlertManager Configuration Documentation](https://prometheus.io/docs/alerting/latest/configuration/)
- [Grafana Dashboards](http://localhost:3000/d/trunkline-overview)
- [Prometheus Web Interface](http://localhost:9090/alerts)

## Contact Information

For questions or suggestions, contact:
- Operations Team: https://github.com/Lincoln-cn/Trunkline/issues
- Development Team: https://github.com/Lincoln-cn/Trunkline/issues
- Trunkline Team: https://github.com/Lincoln-cn/Trunkline/issues