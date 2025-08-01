# Spring Cloud Config Server - Batch Token Solution (Config Server Only)

## Problem
- Using Spring Cloud Config Server with Vault backend
- Vault uses batch tokens with 20-minute TTL  
- No additional Vault lease dependencies available
- Need solution using only Config Server's built-in Vault support

## Solution: Use Config Server's Built-in Token Refresh

Spring Cloud Config Server has its own token management. We configure it to refresh tokens before they expire.

## Simple Configuration Solution

### application.yml (Config Server Only)
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
            # Vault-specific settings for batch tokens
            vault:
              authentication: APPROLE
              app-role:
                role-id: ${VAULT_ROLE_ID}
                secret-id: ${VAULT_SECRET_ID}
              # Force short token lifetime to trigger re-authentication
              token-ttl: 900000  # 15 minutes in milliseconds
          - type: s3
            uri: ${S3_URI}
            bucket: ${S3_BUCKET}
            order: 2

# Alternative: Use application properties for more control
management:
  endpoints:
    web:
      exposure:
        include: health,refresh

logging:
  level:
    org.springframework.cloud.config: DEBUG
    org.springframework.web.client.RestTemplate: DEBUG
```

## Even Simpler - Use Refresh Endpoint

Since Config Server doesn't have sophisticated token management for batch tokens, use the refresh mechanism:

### application.yml (Minimal)
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
            vault:
              authentication: APPROLE
              app-role:
                role-id: ${VAULT_ROLE_ID}
                secret-id: ${VAULT_SECRET_ID}
          - type: s3
            uri: ${S3_URI}
            bucket: ${S3_BUCKET}
            order: 2

# Enable refresh endpoint
management:
  endpoints:
    web:
      exposure:
        include: health,refresh
  endpoint:
    refresh:
      enabled: true
```

## Refresh Strategy Solutions

### Option 1: Cron Job Refresh (Simple)
```bash
#!/bin/bash
# refresh-config-server.sh
# Run this every 15 minutes via cron

curl -X POST http://localhost:8888/actuator/refresh
echo "Config server refreshed at $(date)"
```

**Cron setup:**
```bash
# Edit crontab
crontab -e

# Add this line to refresh every 15 minutes
*/15 * * * * /path/to/refresh-config-server.sh >> /var/log/config-refresh.log 2>&1
```

### Option 2: Application Properties Approach
```properties
# application.properties (if you prefer properties over YAML)
server.port=8888
spring.application.name=config-server

# Vault configuration
spring.cloud.config.server.composite[0].type=vault
spring.cloud.config.server.composite[0].uri=${VAULT_URI:http://localhost:8200}
spring.cloud.config.server.composite[0].backend=secret
spring.cloud.config.server.composite[0].default-key=application
spring.cloud.config.server.composite[0].profile-separator=/
spring.cloud.config.server.composite[0].order=1
spring.cloud.config.server.composite[0].vault.authentication=APPROLE
spring.cloud.config.server.composite[0].vault.app-role.role-id=${VAULT_ROLE_ID}
spring.cloud.config.server.composite[0].vault.app-role.secret-id=${VAULT_SECRET_ID}

# S3 fallback
spring.cloud.config.server.composite[1].type=s3
spring.cloud.config.server.composite[1].uri=${S3_URI}
spring.cloud.config.server.composite[1].bucket=${S3_BUCKET}
spring.cloud.config.server.composite[1].order=2

# Enable refresh
management.endpoints.web.exposure.include=health,refresh
management.endpoint.refresh.enabled=true
```

### Option 3: Docker/Kubernetes Approach
```yaml
# docker-compose.yml
version: '3.8'
services:
  config-server:
    image: your-config-server:latest
    ports:
      - "8888:8888"
    environment:
      - VAULT_URI=http://vault:8200
      - VAULT_ROLE_ID=${VAULT_ROLE_ID}
      - VAULT_SECRET_ID=${VAULT_SECRET_ID}
      - S3_URI=${S3_URI}
      - S3_BUCKET=${S3_BUCKET}
    restart: unless-stopped

  # Sidecar container to refresh tokens
  config-refresher:
    image: curlimages/curl:latest
    command: |
      sh -c "
        while true; do
          sleep 900  # 15 minutes
          curl -X POST http://config-server:8888/actuator/refresh || echo 'Refresh failed'
          echo 'Refreshed at $(date)'
        done
      "
    depends_on:
      - config-server
```

## Testing the Solution

### 1. Test configuration access
```bash
# Get configuration
curl http://localhost:8888/myapp/default

# You should see JSON response with config from Vault
```

### 2. Test refresh endpoint
```bash
# Manual refresh
curl -X POST http://localhost:8888/actuator/refresh

# Should return array of refreshed properties
```

### 3. Monitor for 403 errors
```bash
# Watch logs
tail -f logs/application.log

# Test after 18+ minutes without refresh
curl http://localhost:8888/myapp/default
# This might fail with 403 if token expired

# Then refresh and try again
curl -X POST http://localhost:8888/actuator/refresh
curl http://localhost:8888/myapp/default
# Should work again
```

## Kubernetes CronJob Solution

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: config-server-refresh
spec:
  schedule: "*/15 * * * *"  # Every 15 minutes
  jobTemplate:
    spec:
      template:
        spec:
          containers:
          - name: refresh
            image: curlimages/curl:latest
            command:
            - /bin/sh
            - -c
            - |
              curl -X POST http://config-server:8888/actuator/refresh
              echo "Config server refreshed at $(date)"
          restartPolicy: OnFailure
```

## Why This Works

1. **Config Server's refresh endpoint** forces it to re-authenticate with Vault
2. **New AppRole authentication** gets a fresh batch token
3. **15-minute refresh cycle** ensures tokens don't expire
4. **S3 fallback** provides resilience if Vault is down
5. **No additional dependencies** needed - uses only Config Server features

## Pros/Cons

**Pros:**
✅ Uses only Config Server built-in features  
✅ No additional Vault dependencies  
✅ Simple external refresh mechanism  
✅ Works with existing infrastructure  

**Cons:**
❌ Requires external refresh mechanism (cron/sidecar)  
❌ Brief service interruption during refresh  
❌ Manual setup for refresh automation  

This solution works within Config Server's limitations while solving the batch token expiration problem.