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