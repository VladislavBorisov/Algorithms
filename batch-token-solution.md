# Spring Cloud Config Server - Batch Token Solution

## Problem Description
You cannot change Vault configuration from batch tokens to service tokens, but batch tokens have a fixed 20-minute TTL and cannot be renewed, causing 403 Forbidden errors.

## Root Cause
- Batch tokens are non-renewable by design
- After 20 minutes, the token expires and cannot be extended
- Spring Cloud Config Server loses access to Vault

## Solution: Proactive Re-authentication Strategy

Since batch tokens cannot be renewed, we need to implement a strategy that:
1. Monitors token expiration time
2. Proactively re-authenticates before token expires
3. Handles authentication failures gracefully
4. Maintains continuous service availability

## Implementation Strategy

### 1. Modified Token Manager for Batch Tokens
Instead of trying to renew tokens, we'll re-authenticate periodically.

### 2. Shorter Re-authentication Intervals
Re-authenticate every 15 minutes (5 minutes before the 20-minute expiry).

### 3. Graceful Degradation
If Vault becomes unavailable, fall back to S3 backend only.

### 4. Health Monitoring
Monitor token age and authentication status.

## Code Implementation

### Updated VaultTokenManager.java
```java
package com.example.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.vault.authentication.AppRoleAuthentication;
import org.springframework.vault.authentication.AppRoleAuthenticationOptions;
import org.springframework.vault.authentication.ClientAuthentication;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.config.AbstractVaultConfiguration;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultToken;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Configuration
public class BatchTokenVaultManager extends AbstractVaultConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(BatchTokenVaultManager.class);
    
    // Re-authenticate every 15 minutes (5 minutes before 20-minute expiry)
    private static final int REAUTH_INTERVAL_MINUTES = 15;

    @Value("${vault.uri:http://localhost:8200}")
    private String vaultUri;

    @Value("${vault.app-role.role-id}")
    private String roleId;

    @Value("${vault.app-role.secret-id}")
    private String secretId;

    private VaultTemplate vaultTemplate;
    private VaultToken currentToken;
    private Instant tokenCreatedAt;
    private final AtomicBoolean isAuthenticated = new AtomicBoolean(false);
    private final AtomicBoolean authenticationInProgress = new AtomicBoolean(false);

    @Override
    public VaultEndpoint vaultEndpoint() {
        return VaultEndpoint.from(URI.create(vaultUri));
    }

    @Override
    public ClientAuthentication clientAuthentication() {
        AppRoleAuthenticationOptions options = AppRoleAuthenticationOptions.builder()
                .roleId(roleId)
                .secretId(secretId)
                .build();
        return new AppRoleAuthentication(options, restOperations());
    }

    @PostConstruct
    public void initializeVault() {
        authenticateWithVault();
    }

    @Scheduled(fixedRate = REAUTH_INTERVAL_MINUTES, timeUnit = TimeUnit.MINUTES)
    public void periodicReAuthentication() {
        logger.info("Performing periodic re-authentication with Vault");
        authenticateWithVault();
    }

    // Also check more frequently in case of issues
    @Scheduled(fixedRate = 5, timeUnit = TimeUnit.MINUTES)
    public void checkTokenStatus() {
        if (currentToken == null || tokenCreatedAt == null) {
            logger.warn("No valid token found, attempting re-authentication");
            authenticateWithVault();
            return;
        }

        long minutesSinceCreation = ChronoUnit.MINUTES.between(tokenCreatedAt, Instant.now());
        logger.debug("Token age: {} minutes", minutesSinceCreation);

        // If token is older than 18 minutes, re-authenticate immediately
        if (minutesSinceCreation >= 18) {
            logger.warn("Token is {} minutes old, re-authenticating immediately", minutesSinceCreation);
            authenticateWithVault();
        }
    }

    private synchronized void authenticateWithVault() {
        if (authenticationInProgress.get()) {
            logger.debug("Authentication already in progress, skipping");
            return;
        }

        try {
            authenticationInProgress.set(true);
            logger.info("Authenticating with Vault using AppRole");

            // Create new VaultTemplate and authenticate
            VaultTemplate newVaultTemplate = new VaultTemplate(vaultEndpoint(), clientAuthentication());
            VaultToken newToken = clientAuthentication().login();

            if (newToken != null) {
                this.vaultTemplate = newVaultTemplate;
                this.currentToken = newToken;
                this.tokenCreatedAt = Instant.now();
                this.isAuthenticated.set(true);
                
                logger.info("Successfully authenticated with Vault. Token created at: {}", tokenCreatedAt);
                
                // Log token type for debugging
                try {
                    String tokenType = vaultTemplate.opsForToken().lookup(currentToken).getType();
                    logger.info("Token type: {}", tokenType);
                } catch (Exception e) {
                    logger.warn("Could not determine token type: {}", e.getMessage());
                }
            } else {
                logger.error("Authentication with Vault failed - received null token");
                this.isAuthenticated.set(false);
            }
        } catch (Exception e) {
            logger.error("Failed to authenticate with Vault", e);
            this.isAuthenticated.set(false);
            // Don't throw exception - let the application continue with S3 backend
        } finally {
            authenticationInProgress.set(false);
        }
    }

    public VaultToken getCurrentToken() {
        return currentToken;
    }

    public VaultTemplate getVaultTemplate() {
        return vaultTemplate;
    }

    public boolean isAuthenticated() {
        return isAuthenticated.get() && currentToken != null;
    }

    public long getTokenAgeMinutes() {
        if (tokenCreatedAt == null) {
            return -1;
        }
        return ChronoUnit.MINUTES.between(tokenCreatedAt, Instant.now());
    }

    public Instant getTokenCreatedAt() {
        return tokenCreatedAt;
    }

    // Method to force re-authentication (useful for testing or manual triggers)
    public void forceReAuthentication() {
        logger.info("Force re-authentication requested");
        authenticateWithVault();
    }
}
```

### Enhanced Health Indicator
```java
package com.example.health;

import com.example.config.BatchTokenVaultManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuator.health.Health;
import org.springframework.boot.actuator.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component("vaultBatch")
public class VaultBatchHealthIndicator implements HealthIndicator {

    private static final Logger logger = LoggerFactory.getLogger(VaultBatchHealthIndicator.class);

    @Autowired
    private BatchTokenVaultManager vaultManager;

    @Override
    public Health health() {
        try {
            if (!vaultManager.isAuthenticated()) {
                return Health.down()
                        .withDetail("status", "Not authenticated")
                        .withDetail("token_available", false)
                        .build();
            }

            long tokenAge = vaultManager.getTokenAgeMinutes();
            String status;
            Health.Builder healthBuilder;

            if (tokenAge >= 18) {
                status = "Token expires soon - re-authentication needed";
                healthBuilder = Health.down();
            } else if (tokenAge >= 15) {
                status = "Token aging - scheduled for re-authentication";
                healthBuilder = Health.up();
            } else {
                status = "Token healthy";
                healthBuilder = Health.up();
            }

            // Test actual connectivity
            try {
                vaultManager.getVaultTemplate().opsForSys().health();
            } catch (Exception e) {
                logger.warn("Vault connectivity test failed", e);
                return healthBuilder
                        .withDetail("status", status + " - Connectivity issues")
                        .withDetail("token_age_minutes", tokenAge)
                        .withDetail("token_created_at", vaultManager.getTokenCreatedAt())
                        .withDetail("connectivity_error", e.getMessage())
                        .build();
            }

            return healthBuilder
                    .withDetail("status", status)
                    .withDetail("token_age_minutes", tokenAge)
                    .withDetail("token_created_at", vaultManager.getTokenCreatedAt())
                    .withDetail("max_token_ttl_minutes", 20)
                    .withDetail("reauth_interval_minutes", 15)
                    .build();

        } catch (Exception e) {
            logger.error("Vault health check failed", e);
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .withDetail("status", "Health check failed")
                    .build();
        }
    }
}
```

### Graceful Degradation Configuration
```yaml
spring:
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
            # Add fault tolerance
            fail-fast: false
          - type: s3
            uri: ${S3_URI}
            bucket: ${S3_BUCKET}
            order: 2
            # S3 as fallback when Vault fails
            fail-fast: false

# Enhanced logging for batch token debugging
logging:
  level:
    com.example.config.BatchTokenVaultManager: DEBUG
    org.springframework.vault: INFO
    org.springframework.cloud.config: INFO
```

### Manual Re-authentication Endpoint
```java
package com.example.controller;

import com.example.config.BatchTokenVaultManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.HashMap;
import java.util.Map;

@RestController
public class VaultManagementController {

    @Autowired
    private BatchTokenVaultManager vaultManager;

    @PostMapping("/management/vault/reauth")
    public ResponseEntity<Map<String, Object>> forceReAuthentication() {
        Map<String, Object> response = new HashMap<>();
        try {
            vaultManager.forceReAuthentication();
            response.put("status", "success");
            response.put("message", "Re-authentication triggered");
            response.put("authenticated", vaultManager.isAuthenticated());
            response.put("token_age_minutes", vaultManager.getTokenAgeMinutes());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("status", "error");
            response.put("message", "Re-authentication failed: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    @GetMapping("/management/vault/status")
    public ResponseEntity<Map<String, Object>> getVaultStatus() {
        Map<String, Object> response = new HashMap<>();
        response.put("authenticated", vaultManager.isAuthenticated());
        response.put("token_age_minutes", vaultManager.getTokenAgeMinutes());
        response.put("token_created_at", vaultManager.getTokenCreatedAt());
        response.put("max_ttl_minutes", 20);
        response.put("reauth_interval_minutes", 15);
        return ResponseEntity.ok(response);
    }
}
```

## Deployment Strategy

### 1. Monitoring Setup
```bash
# Check vault status
curl http://localhost:8888/management/vault/status

# Force re-authentication if needed
curl -X POST http://localhost:8888/management/vault/reauth

# Health check
curl http://localhost:8888/actuator/health/vaultBatch
```

### 2. Alerting Configuration
Set up alerts for:
- Token age > 18 minutes
- Authentication failures
- Vault connectivity issues

### 3. Kubernetes Deployment (if applicable)
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: config-server
spec:
  replicas: 2
  template:
    spec:
      containers:
      - name: config-server
        image: config-server:latest
        env:
        - name: VAULT_ROLE_ID
          valueFrom:
            secretKeyRef:
              name: vault-secrets
              key: role-id
        - name: VAULT_SECRET_ID
          valueFrom:
            secretKeyRef:
              name: vault-secrets
              key: secret-id
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8888
          initialDelaySeconds: 60
          periodSeconds: 30
        readinessProbe:
          httpGet:
            path: /actuator/health/vaultBatch
            port: 8888
          initialDelaySeconds: 30
          periodSeconds: 15
```

## Key Benefits of This Approach

1. **Works with Batch Tokens**: No need to change Vault configuration
2. **Proactive Re-authentication**: Prevents 403 errors by re-authenticating before expiry
3. **Graceful Degradation**: Falls back to S3 when Vault is unavailable
4. **Monitoring**: Comprehensive health checks and status endpoints
5. **Manual Override**: Ability to force re-authentication when needed
6. **High Availability**: Multiple re-authentication schedules ensure reliability

## Testing the Solution

```bash
# 1. Start the application
mvn spring-boot:run

# 2. Monitor token age
watch -n 30 'curl -s http://localhost:8888/management/vault/status | jq'

# 3. Watch for re-authentication in logs
tail -f logs/config-server.log | grep -i "authentication\|token"

# 4. Test configuration retrieval
curl http://localhost:8888/myapp/default
```

This solution ensures continuous operation with batch tokens by implementing smart re-authentication timing.