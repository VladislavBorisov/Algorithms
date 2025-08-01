package com.example.health;

import com.example.vault.CustomVaultTokenManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuator.health.Health;
import org.springframework.boot.actuator.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("customVault")
public class CustomVaultHealthIndicator implements HealthIndicator {
    
    @Autowired
    private CustomVaultTokenManager tokenManager;
    
    @Override
    public Health health() {
        try {
            if (tokenManager.isTokenValid()) {
                long tokenAge = tokenManager.getTokenAgeMinutes();
                String status;
                
                if (tokenAge >= 18) {
                    status = "Token expires soon (age: " + tokenAge + " minutes)";
                } else if (tokenAge >= 15) {
                    status = "Token aging (age: " + tokenAge + " minutes)";
                } else {
                    status = "Token healthy (age: " + tokenAge + " minutes)";
                }
                
                return Health.up()
                        .withDetail("status", status)
                        .withDetail("token_age_minutes", tokenAge)
                        .withDetail("token_created_at", tokenManager.getTokenCreatedAt())
                        .withDetail("token_valid", true)
                        .withDetail("max_token_ttl_minutes", 20)
                        .withDetail("refresh_threshold_minutes", 18)
                        .build();
            } else {
                return Health.down()
                        .withDetail("status", "No valid token available")
                        .withDetail("token_valid", false)
                        .withDetail("token_age_minutes", tokenManager.getTokenAgeMinutes())
                        .build();
            }
        } catch (Exception e) {
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .withDetail("status", "Health check failed")
                    .build();
        }
    }
}