# Spring Cloud Config Server - Vault Batch Token TTL Solution

## Problem Description
Your Spring Cloud Config Server uses a composite backend with Vault and S3, with AppRole authentication. The issue occurs when Vault switches from AppRole auth to batch token with a 20-minute TTL, causing 403 Forbidden errors and config server disconnection.

## Root Cause
Batch tokens in Vault have a fixed TTL and cannot be renewed. Once they expire (after 20 minutes), the Config Server loses access to Vault, causing the 403 Forbidden error.

## Solution 1: Use Service Tokens Instead of Batch Tokens

### Step 1: Configure Vault to Use Service Tokens
In your Vault configuration, ensure you're using service tokens instead of batch tokens:

```hcl
# vault-policy.hcl
path "secret/data/myapp/*" {
  capabilities = ["read"]
}

path "auth/token/create" {
  capabilities = ["create", "update"]
}

path "auth/token/renew" {
  capabilities = ["update"]
}
```

### Step 2: Update AppRole Configuration
```bash
# Create AppRole with service token type
vault write auth/approle/role/config-server \
    token_policies="config-server-policy" \
    token_ttl=1h \
    token_max_ttl=24h \
    token_type=service \
    bind_secret_id=true
```

## Solution 2: Spring Cloud Config Server Configuration

### application.yml
```yaml
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
          - type: s3
            uri: ${S3_URI}
            bucket: ${S3_BUCKET}
            order: 2
        vault:
          authentication: APPROLE
          app-role:
            role-id: ${VAULT_ROLE_ID}
            secret-id: ${VAULT_SECRET_ID}
          uri: ${VAULT_URI:http://localhost:8200}
          connection-timeout: 5000
          read-timeout: 15000
          token-ttl: 3600s
          token-renewal-threshold: 300s  # Renew 5 minutes before expiry
          
# Additional Vault configuration for token management
vault:
  authentication: APPROLE
  app-role:
    role-id: ${VAULT_ROLE_ID}
    secret-id: ${VAULT_SECRET_ID}
  uri: ${VAULT_URI:http://localhost:8200}
  lease:
    min-renewal: 300s
    expiry-threshold: 600s
  connection-timeout: 5000
  read-timeout: 15000

management:
  endpoints:
    web:
      exposure:
        include: health,info,vault
  endpoint:
    health:
      show-details: always
```

## Solution 3: Custom Vault Token Manager (Recommended)

### VaultTokenManager.java
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
import org.springframework.vault.core.lease.LeaseEndpoints;
import org.springframework.vault.support.VaultToken;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
@Configuration
public class VaultTokenManager extends AbstractVaultConfiguration {

    @Value("${vault.uri:http://localhost:8200}")
    private String vaultUri;

    @Value("${vault.app-role.role-id}")
    private String roleId;

    @Value("${vault.app-role.secret-id}")
    private String secretId;

    private VaultTemplate vaultTemplate;
    private VaultToken currentToken;

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
        this.vaultTemplate = new VaultTemplate(vaultEndpoint(), clientAuthentication());
        this.currentToken = clientAuthentication().login();
    }

    @Scheduled(fixedRate = 10, timeUnit = TimeUnit.MINUTES)
    public void renewToken() {
        try {
            if (currentToken != null && shouldRenewToken()) {
                VaultToken renewedToken = vaultTemplate.opsForToken().renew(currentToken);
                if (renewedToken != null) {
                    this.currentToken = renewedToken;
                    System.out.println("Vault token renewed successfully");
                } else {
                    // Token couldn't be renewed, re-authenticate
                    reauthenticate();
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to renew token, re-authenticating: " + e.getMessage());
            reauthenticate();
        }
    }

    private boolean shouldRenewToken() {
        // Check if token is close to expiration (within 5 minutes)
        return currentToken.getLeaseDuration().compareTo(Duration.ofMinutes(5)) <= 0;
    }

    private void reauthenticate() {
        try {
            this.currentToken = clientAuthentication().login();
            System.out.println("Re-authenticated with Vault successfully");
        } catch (Exception e) {
            System.err.println("Failed to re-authenticate with Vault: " + e.getMessage());
        }
    }

    public VaultToken getCurrentToken() {
        return currentToken;
    }
}
```

### Enhanced Configuration Class
```java
package com.example.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.config.server.environment.VaultEnvironmentRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.vault.core.VaultTemplate;

@Configuration
public class VaultConfig {

    @Autowired
    private VaultTokenManager vaultTokenManager;

    @Bean
    @Primary
    public VaultTemplate vaultTemplate() {
        return new VaultTemplate(
            vaultTokenManager.vaultEndpoint(),
            () -> vaultTokenManager.getCurrentToken()
        );
    }
}
```

## Solution 4: Monitoring and Health Checks

### VaultHealthIndicator.java
```java
package com.example.health;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuator.health.Health;
import org.springframework.boot.actuator.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.vault.core.VaultTemplate;

@Component("vault")
public class VaultHealthIndicator implements HealthIndicator {

    @Autowired
    private VaultTemplate vaultTemplate;

    @Override
    public Health health() {
        try {
            // Test vault connectivity
            vaultTemplate.opsForSys().health();
            return Health.up()
                    .withDetail("status", "Connected to Vault")
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .withDetail("status", "Cannot connect to Vault")
                    .build();
        }
    }
}
```

## Solution 5: Deployment Configuration

### docker-compose.yml (for testing)
```yaml
version: '3.8'
services:
  vault:
    image: vault:1.15.0
    cap_add:
      - IPC_LOCK
    environment:
      VAULT_DEV_ROOT_TOKEN_ID: myroot
      VAULT_DEV_LISTEN_ADDRESS: 0.0.0.0:8200
      VAULT_ADDR: http://0.0.0.0:8200
    ports:
      - "8200:8200"
    command: vault server -dev

  config-server:
    build: .
    environment:
      VAULT_URI: http://vault:8200
      VAULT_ROLE_ID: ${VAULT_ROLE_ID}
      VAULT_SECRET_ID: ${VAULT_SECRET_ID}
      S3_URI: ${S3_URI}
      S3_BUCKET: ${S3_BUCKET}
    ports:
      - "8888:8888"
    depends_on:
      - vault
```

## Implementation Steps

1. **Update Vault AppRole Configuration** to use service tokens instead of batch tokens
2. **Implement Custom Token Manager** for automatic token renewal
3. **Configure Spring Cloud Config** with proper timeout and renewal settings
4. **Add Health Checks** to monitor Vault connectivity
5. **Test Token Renewal** to ensure it works before the 20-minute TTL expires

## Testing the Solution

```bash
# Test token renewal
curl -X GET "http://localhost:8888/actuator/health/vault"

# Test configuration retrieval
curl -X GET "http://localhost:8888/myapp/default"

# Monitor logs for token renewal messages
docker logs config-server -f | grep -i vault
```

This solution ensures that your Spring Cloud Config Server maintains continuous access to Vault by properly managing token renewal and falling back to re-authentication when needed.