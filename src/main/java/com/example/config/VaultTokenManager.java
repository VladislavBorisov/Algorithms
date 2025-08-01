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
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
@Configuration
public class VaultTokenManager extends AbstractVaultConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(VaultTokenManager.class);

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
        try {
            this.vaultTemplate = new VaultTemplate(vaultEndpoint(), clientAuthentication());
            this.currentToken = clientAuthentication().login();
            logger.info("Vault initialized successfully with token");
        } catch (Exception e) {
            logger.error("Failed to initialize Vault", e);
            throw new RuntimeException("Vault initialization failed", e);
        }
    }

    @Scheduled(fixedRate = 10, timeUnit = TimeUnit.MINUTES)
    public void renewToken() {
        try {
            if (currentToken != null && shouldRenewToken()) {
                logger.info("Attempting to renew Vault token");
                VaultToken renewedToken = vaultTemplate.opsForToken().renew(currentToken);
                if (renewedToken != null) {
                    this.currentToken = renewedToken;
                    logger.info("Vault token renewed successfully");
                } else {
                    logger.warn("Token renewal returned null, re-authenticating");
                    reauthenticate();
                }
            }
        } catch (Exception e) {
            logger.error("Failed to renew token, re-authenticating: {}", e.getMessage());
            reauthenticate();
        }
    }

    private boolean shouldRenewToken() {
        if (currentToken == null || currentToken.getLeaseDuration() == null) {
            return true;
        }
        // Check if token is close to expiration (within 5 minutes)
        boolean shouldRenew = currentToken.getLeaseDuration().compareTo(Duration.ofMinutes(5)) <= 0;
        if (shouldRenew) {
            logger.info("Token is close to expiration, renewal needed");
        }
        return shouldRenew;
    }

    private void reauthenticate() {
        try {
            logger.info("Re-authenticating with Vault");
            this.currentToken = clientAuthentication().login();
            logger.info("Re-authenticated with Vault successfully");
        } catch (Exception e) {
            logger.error("Failed to re-authenticate with Vault", e);
            throw new RuntimeException("Vault re-authentication failed", e);
        }
    }

    public VaultToken getCurrentToken() {
        return currentToken;
    }

    public VaultTemplate getVaultTemplate() {
        return vaultTemplate;
    }
}