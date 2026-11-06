# Error Codes Reference

<!-- 版本信息 -->
> **Document Version**: 1.1.0
> **Last Updated**: 2026-10-28
> **Applicable Version**: v3.3.1
> **Author**: AI Assistant

Trunkline uses a standardized error code system to identify various error conditions. All error responses include an error code field for client-side error handling.

> ⚠️ **Response shape differs by entry surface**: `/api/**` (console surface) uses the `RouterResponse` shape in this table (`errorCode` field); `/v1/**` (OpenAI surface) uses the nested `{"error":{"message","type","code"}}` shape (see [Universal API](universal-api.md)). Two known exceptions do not follow this table: the management-plane rate-limit 429 returns `{"code":"RATE_LIMIT_EXCEEDED","message":...}` (`AdminApiRateLimiter`, **flat body, no `error` wrapper**); the exception-handler fallback returns `{"code":"500"}` (`ReactiveGlobalExceptionHandler`).

## Error Response Format

```json
{
  "success": false,
  "message": "Error description",
  "errorCode": "ERROR_CODE",
  "data": null
}
```

## HTTP Status Code Mapping

| HTTP Status | Description | Error Category |
|-------------|-------------|----------------|
| 400 | Bad Request | Validation Error |
| 401 | Unauthorized | Authentication Error |
| 403 | Forbidden | Authorization Error |
| 404 | Not Found | Resource Error |
| 409 | Conflict | Business Error |
| 429 | Too Many Requests | Rate Limit Error |
| 500 | Internal Server Error | System Error |
| 502 | Bad Gateway | Downstream Error |
| 503 | Service Unavailable | Downstream Error |

---

## Authentication Error Codes

**HTTP Status**: 401 Unauthorized

| Error Code | Description | Common Cause | Solution |
|------------|-------------|--------------|----------|
| `INVALID_API_KEY` | Invalid API Key | API Key format error or not found | Check API Key correctness |
| `EXPIRED_API_KEY` | API Key expired | API Key exceeded validity period | Regenerate API Key |
| `MISSING_API_KEY` | Missing API Key | Request header missing API Key | Add `X-API-Key` header |
| `JWT_INVALID` | Invalid JWT token | JWT format error or invalid signature | Check JWT format and signature |
| `JWT_EXPIRED` | JWT token expired | JWT exceeded validity period | Refresh or obtain new JWT |
| `JWT_BLACKLISTED` | Token blacklisted | JWT has been revoked | Re-login to obtain new token |
| `EMPTY_JWT_TOKEN` | No JWT provided | Request header missing `Jairouter_Token` | Add `Jairouter_Token` header |
| `EMPTY_API_KEY` | No API Key provided | Request header missing `X-API-Key` | Add `X-API-Key` header |
| `API_KEY_DISABLED` | API Key disabled | The key has been deactivated | Enable or replace the key |
| `API_KEY_AUTH_FAILED` | API Key authentication failed | Key does not match its account | Check key ownership |
| `AUTH_FAILED` | Authentication failed | Generic authentication failure | Check authentication credentials |
| `AUTH_ERROR` | Authentication error | Authentication process exception | Check authentication service status |

> The old rows `INVALID_JWT_TOKEN` / `EXPIRED_JWT_TOKEN` / `BLACKLISTED_TOKEN` exist only as constant declarations in `AuthenticationException`; the 401 codes actually emitted by the authentication chain are the rows above
> (`DefaultJwtTokenValidator.java:76,85,120`, `CustomReactiveAuthenticationManager.java:57,67,92,115`).

**Example Response**:
```json
{
  "success": false,
  "message": "Invalid API Key",
  "errorCode": "INVALID_API_KEY",
  "data": null
}
```

---

## Authorization Error Codes

**HTTP Status**: 403 Forbidden

| Error Code | Description | Common Cause | Solution |
|------------|-------------|--------------|----------|
| `INSUFFICIENT_PERMISSIONS` | Insufficient permissions | User lacks required permissions | Contact admin to assign permissions |
| `ACCESS_DENIED` | Access denied | Attempting to access unauthorized resource | Check resource access permissions |
| `RESOURCE_FORBIDDEN` | Resource forbidden | Resource set as forbidden | Contact resource owner |
| `FORBIDDEN` | Forbidden | Generic authorization failure | Check user role and permissions |

**Example Response**:
```json
{
  "success": false,
  "message": "Insufficient permissions, required: admin",
  "errorCode": "INSUFFICIENT_PERMISSIONS",
  "data": null
}
```

---

## Sanitization Error Codes

**HTTP Status**: 500 Internal Server Error

| Error Code | Description | Common Cause | Solution |
|------------|-------------|--------------|----------|
| `SANITIZATION_FAILED` | Data sanitization failed | Sanitization process exception | Check logs for details |
| `INVALID_SANITIZATION_RULE` | Invalid sanitization rule | Rule configuration error | Check rule configuration |
| `RULE_COMPILATION_FAILED` | Rule compilation failed | Regex syntax error | Fix regex pattern |
| `CONTENT_PROCESSING_FAILED` | Content processing failed | Unsupported content format | Check content type |

**Example Response**:
```json
{
  "success": false,
  "message": "Data sanitization failed: unsupported content format",
  "errorCode": "SANITIZATION_FAILED",
  "data": null
}
```

---

## Resource Error Codes

**HTTP Status**: 404 Not Found

| Error Code | Description | Common Cause | Solution |
|------------|-------------|--------------|----------|
| `NOT_FOUND` | Resource not found | Requested resource doesn't exist (e.g. `model` is not a configured model group) | Check resource ID or path |

---

## Validation Error Codes

**HTTP Status**: 400 Bad Request

| Error Code | Description | Common Cause | Solution |
|------------|-------------|--------------|----------|
| `INVALID_REQUEST` | Invalid request | Parameter format or value error | Check request parameters |
| `INVALID_PARAM` | Invalid parameter | Parameter value out of range | Check parameter constraints |

> The old `VALIDATION_ERROR` (only an enum value of `ValidationFailureType`, never emitted as a response code, see `ApiKeyValidator.java:344`) and `INVALID_PARAMETER` / `MISSING_PARAMETER` / `INVALID_FORMAT` (no matches anywhere in the codebase) have been removed.

---

## Business Error Codes

| Error Code | Description | HTTP Status | Solution |
|------------|-------------|-------------|----------|
| `CONFLICT` | Resource conflict | 409 | Check resource state |
| `RATE_LIMIT_EXCEEDED` | Rate limit triggered | 429 | Reduce request frequency |

---

## Downstream Service Error Codes

**HTTP Status**: 502/503

| Error Code Pattern | Description | Solution |
|--------------------|-------------|----------|
| `5xx` | Downstream service error | Check downstream service status |
| `502` | Bad gateway | Check network connection |
| `503` | Service unavailable | Wait for service recovery |
| `504` | Gateway timeout | Increase timeout or check downstream response |

---

## Rate Limit Error Codes

**HTTP Status**: 429 Too Many Requests

| Error Code | Description | Response Header | Solution |
|------------|-------------|-----------------|----------|
| `RATE_LIMIT_EXCEEDED` | Rate limit exceeded | `Retry-After` / `X-Quota-*` | Wait for token recovery |

> There are **no** `X-RateLimit-Reset` / `X-RateLimit-Remaining` headers (no such headers anywhere in the repo); quota over-limit actually writes
> `Retry-After` and `X-Quota-*` (limit / remaining / window), see `ServiceRequestHandler.java:692-695`.
> The old rows `GLOBAL_RATE_LIMIT` / `SERVICE_RATE_LIMIT` / `INSTANCE_RATE_LIMIT` have no matches in the codebase (the last one is only the JPA table name `instance_rate_limit`, see `InstanceRateLimitEntity.java:26`) and have been removed.

---

## Circuit Breaker Error Codes

**The circuit breaker has no dedicated error code.** Circuit-breaker state is exposed via tracing span attributes (lowercase `circuit_breaker_open`, see
`CircuitBreakerTracingDelegate.java:132`) and the monitoring endpoint `GET /api/monitoring/circuit-breaker/stats`;
when the breaker is open, the request is handled as downstream unavailable.

> The old rows `CIRCUIT_BREAKER_OPEN` / `CIRCUIT_BREAKER_HALF_OPEN` / `SERVICE_DEGRADED` as **error codes** have no matches in the codebase and have been removed.

---

## System Error Codes

**HTTP Status**: 500 Internal Server Error

| Error Code | Description | Common Cause | Solution |
|------------|-------------|--------------|----------|
| `INTERNAL_ERROR` | Internal server error | Unexpected exception | Check service logs |

---

## Error Handling Best Practices

### 1. Client-Side Error Handling

```javascript
// JavaScript Example
async function handleApiResponse(response) {
  const data = await response.json();

  if (!data.success) {
    switch (data.errorCode) {
      case 'INVALID_API_KEY':
      case 'EXPIRED_API_KEY':
      case 'JWT_INVALID':
      case 'JWT_EXPIRED':
        // Redirect to login page
        window.location.href = '/login';
        break;
      case 'RATE_LIMIT_EXCEEDED':
        // Wait and retry (only Retry-After exists, not X-RateLimit-Reset)
        const retryAfter = response.headers.get('Retry-After') || '5';
        await sleep(parseInt(retryAfter, 10) * 1000);
        return retryRequest();
        break;
      default:
        // Show error message
        showError(data.message);
    }
    return;
  }

  return data.data;
}
```

### 2. Server-Side Error Handling

```java
// Java Example
@RestControllerAdvice
public class ErrorHandler {

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthException(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(new ErrorResponse(ex.getMessage(), ex.getErrorCode()));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
            .body(new ErrorResponse("Too many requests", "RATE_LIMIT_EXCEEDED"));
    }
}
```

### 3. Error Logging

```java
// Log error information
log.error("Authentication failed: errorCode={}, message={}",
    ex.getErrorCode(), ex.getMessage());
```

---

## Quick Reference by Status Code

| Status | Error Codes |
|--------|-------------|
| 400 | `INVALID_REQUEST`, `INVALID_PARAM` |
| 401 | `INVALID_API_KEY`, `EXPIRED_API_KEY`, `MISSING_API_KEY`, `EMPTY_API_KEY`, `EMPTY_JWT_TOKEN`, `API_KEY_DISABLED`, `API_KEY_AUTH_FAILED`, `JWT_INVALID`, `JWT_EXPIRED`, `JWT_BLACKLISTED`, `AUTH_FAILED`, `AUTH_ERROR` |
| 403 | `INSUFFICIENT_PERMISSIONS`, `ACCESS_DENIED`, `RESOURCE_FORBIDDEN`, `FORBIDDEN` |
| 404 | `NOT_FOUND` |
| 409 | `CONFLICT` (any code starting with `CONFLICT` maps to 409) |
| 429 | `RATE_LIMIT_EXCEEDED` |
| 500 | `INTERNAL_ERROR`, `SANITIZATION_FAILED`, `RESTART_REQUIRED` |
| 502/503 | Downstream service errors (503 also covers `PoolUnavailableException` when a group has no healthy member) |

---

## Changelog

| Version | Date | Changes |
|---------|------|---------|
| 1.1.0 | 2026-10-28 | Verified against v3.3.1 code: fixed the quick-reference table that still listed removed codes; examples now use the real codes `JWT_INVALID`/`JWT_EXPIRED` and `Retry-After`; re-placed the `AUTH_FAILED`/`AUTH_ERROR` rows inside the table |
| 1.0.1 | 2026-06-29 | Update version reference to v2.7.5+, fix incorrect version number |
| 1.0.0 | 2026-05-25 | Initial version with all error code definitions |

---

*Last updated: 2026-10-28*
