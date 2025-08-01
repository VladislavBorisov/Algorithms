package com.example.health;

import com.example.config.VaultTokenManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuator.health.Health;
import org.springframework.boot.actuator.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.vault.core.VaultTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component("vault")
public class VaultHealthIndicator implements HealthIndicator {

    private static final Logger logger = LoggerFactory.getLogger(VaultHealthIndicator.class);

    @Autowired
    private VaultTokenManager vaultTokenManager;

    @Override
    public Health health() {
        try {
            VaultTemplate vaultTemplate = vaultTokenManager.getVaultTemplate();
            if (vaultTemplate != null) {
                // Test vault connectivity by checking health endpoint
                vaultTemplate.opsForSys().health();
                
                // Check if we have a valid token
                var currentToken = vaultTokenManager.getCurrentToken();
                if (currentToken != null) {
                    return Health.up()
                            .withDetail("status", "Connected to Vault")
                            .withDetail("token_available", true)
                            .withDetail("token_ttl", currentToken.getLeaseDuration() != null ? 
                                      currentToken.getLeaseDuration().getSeconds() + "s" : "unknown")
                            .build();
                } else {
                    return Health.down()
                            .withDetail("status", "No valid token available")
                            .withDetail("token_available", false)
                            .build();
                }
            } else {
                return Health.down()
                        .withDetail("error", "VaultTemplate not initialized")
                        .withDetail("status", "Cannot connect to Vault")
                        .build();
            }
        } catch (Exception e) {
            logger.error("Vault health check failed", e);
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .withDetail("status", "Cannot connect to Vault")
                    .build();
        }
    }
}