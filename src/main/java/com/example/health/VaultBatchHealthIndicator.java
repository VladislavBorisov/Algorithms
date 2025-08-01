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