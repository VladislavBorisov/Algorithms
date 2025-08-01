package com.example.health;

import com.example.vault.VaultAuthRefreshConfig;
import org.springframework.boot.actuator.health.Health;
import org.springframework.boot.actuator.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class VaultTokenHealthIndicator implements HealthIndicator {
    
    private final VaultAuthRefreshConfig vaultConfig;
    
    public VaultTokenHealthIndicator(VaultAuthRefreshConfig vaultConfig) {
        this.vaultConfig = vaultConfig;
    }
    
    @Override
    public Health health() {
        try {
            long minutesSinceRefresh = vaultConfig.getMinutesSinceRefresh();
            boolean hasActiveSession = vaultConfig.hasActiveSession();
            Instant lastRefresh = vaultConfig.getLastRefreshTime();
            
            Health.Builder builder = hasActiveSession ? Health.up() : Health.down();
            
            builder.withDetail("hasActiveSession", hasActiveSession)
                   .withDetail("minutesSinceRefresh", minutesSinceRefresh)
                   .withDetail("lastRefreshTime", lastRefresh)
                   .withDetail("shouldRefresh", vaultConfig.shouldRefresh());
            
            if (minutesSinceRefresh > 18) {
                builder.down().withDetail("status", "Token may be expired (18+ minutes old)");
            } else if (minutesSinceRefresh > 15) {
                builder.down().withDetail("status", "Token getting old (15+ minutes old)");
            } else {
                builder.withDetail("status", "Token is fresh");
            }
            
            return builder.build();
            
        } catch (Exception e) {
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .withDetail("status", "Error checking Vault token health")
                    .build();
        }
    }
}