# Simple Spring Cloud Config Server - Batch Token Solution

## Problem
- Vault uses batch tokens with 20-minute TTL
- Batch tokens cannot be renewed
- Need solution only within Spring Cloud Config Server

## Simple Solution: Use Spring's Built-in Token Refresh

Spring Cloud Config Server has built-in mechanisms to handle token expiration. We just need to configure it properly for batch tokens.

## Configuration Only Solution

### application.yml
```yaml
server:
  port: 8888

spring:
  application:
    name: config-server
  cloud:
    config:
      server:
        composite:
          - type: vault
            uri: ${VAULT_URI:http://localhost:8200}
            backend: secret
            default-key: application
            profile-separator: '/'
            order: 1
            # Don't fail if Vault has issues
            fail-fast: false
          - type: s3
            uri: ${S3_URI}
            bucket: ${S3_BUCKET}
            order: 2
            fail-fast: false
        vault:
          authentication: APPROLE
          app-role:
            role-id: ${VAULT_ROLE_ID}
            secret-id: ${VAULT_SECRET_ID}
          uri: ${VAULT_URI:http://localhost:8200}
          # Key settings for batch tokens
          connection-timeout: 5000
          read-timeout: 15000
          # Force re-authentication instead of renewal
          token-ttl: 900s  # 15 minutes (less than batch token TTL)
          token-renewal-threshold: 0s  # Don't try to renew

# Additional Vault configuration
vault:
  authentication: APPROLE
  app-role:
    role-id: ${VAULT_ROLE_ID}
    secret-id: ${VAULT_SECRET_ID}
  uri: ${VAULT_URI:http://localhost:8200}
  # Force frequent re-authentication
  lease:
    min-renewal: 0s      # No renewal
    expiry-threshold: 0s # Re-auth immediately when expired
  connection-timeout: 5000
  read-timeout: 15000

# Logging to see what's happening
logging:
  level:
    org.springframework.vault: DEBUG
    org.springframework.cloud.config: DEBUG
  pattern:
    console: "%d{HH:mm:ss} - %msg%n"

# Health monitoring
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: always
```

## How This Works

### 1. Token TTL Strategy
```yaml
token-ttl: 900s  # 15 minutes (less than 20-minute batch token TTL)
```
- Set token TTL to 15 minutes instead of 20
- This forces Spring to re-authenticate every 15 minutes
- Provides 5-minute safety buffer

### 2. Disable Renewal
```yaml
token-renewal-threshold: 0s  # Don't try to renew
min-renewal: 0s              # No renewal
```
- Tells Spring not to attempt token renewal
- Forces re-authentication instead

### 3. Graceful Fallback
```yaml
fail-fast: false
```
- If Vault fails, continue with S3 backend
- No service interruption

## Testing the Solution

### 1. Check logs for re-authentication
```bash
# Start the application
mvn spring-boot:run

# Watch logs for authentication
tail -f logs/application.log | grep -i "vault\|auth"
```

### 2. Test configuration retrieval
```bash
# Test getting configuration
curl http://localhost:8888/myapp/default

# Test health
curl http://localhost:8888/actuator/health
```

### 3. Monitor timing
Every 15 minutes you should see in logs:
```
15:00:00 - Authenticating with Vault using AppRole
15:15:00 - Authenticating with Vault using AppRole  
15:30:00 - Authenticating with Vault using AppRole
```

## Alternative: Even Simpler with Shorter TTL

If you want to be extra safe, use an even shorter TTL:

```yaml
vault:
  app-role:
    role-id: ${VAULT_ROLE_ID}
    secret-id: ${VAULT_SECRET_ID}
  uri: ${VAULT_URI:http://localhost:8200}
  # Re-authenticate every 10 minutes
  token-ttl: 600s
  token-renewal-threshold: 0s
```

## Why This Works

1. **Spring handles re-authentication automatically** when token expires
2. **No custom code needed** - uses built-in Spring Vault features  
3. **Shorter TTL ensures re-auth before batch token expires**
4. **Graceful degradation** to S3 if Vault unavailable
5. **Simple configuration-only solution**

This approach leverages Spring Cloud Config Server's existing token management while working around the batch token limitation.