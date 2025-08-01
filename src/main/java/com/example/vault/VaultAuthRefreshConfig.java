package com.example.vault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.vault.authentication.AppRoleAuthentication;
import org.springframework.vault.authentication.AppRoleAuthenticationOptions;
import org.springframework.vault.authentication.SessionManager;
import org.springframework.vault.authentication.SimpleSessionManager;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Configuration
public class VaultAuthRefreshConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(VaultAuthRefreshConfig.class);
    
    @Value("${spring.cloud.config.server.vault.uri}")
    private String vaultUri;
    
    @Value("${spring.cloud.config.server.vault.app-role.role-id}")
    private String roleId;
    
    @Value("${spring.cloud.config.server.vault.app-role.secret-id}")
    private String secretId;
    
    private SessionManager sessionManager;
    private Instant lastRefresh;
    
    @PostConstruct
    public void initialize() {
        logger.info("Initializing Vault Auth Refresh Configuration");
        // Initial refresh will happen automatically when VaultTemplate is used
    }
    
    /**
     * Create a custom VaultTemplate with refreshable AppRole authentication
     */
    @Bean
    @Primary
    public VaultTemplate vaultTemplate() {
        VaultEndpoint endpoint = VaultEndpoint.from(URI.create(vaultUri));
        
        // Create AppRole authentication
        AppRoleAuthenticationOptions options = AppRoleAuthenticationOptions.builder()
            .roleId(AppRoleAuthenticationOptions.RoleId.provided(roleId))
            .secretId(AppRoleAuthenticationOptions.SecretId.provided(secretId))
            .build();
            
        AppRoleAuthentication authentication = new AppRoleAuthentication(options, new RestTemplate());
        
        // Create session manager that handles token lifecycle
        this.sessionManager = new SimpleSessionManager(authentication);
        
        logger.info("Created VaultTemplate with refreshable AppRole authentication");
        return new VaultTemplate(endpoint, sessionManager);
    }
    
    /**
     * Scheduled refresh to get new tokens before they expire
     */
    @Scheduled(fixedRate = 15, timeUnit = TimeUnit.MINUTES)
    public void scheduledTokenRefresh() {
        logger.info("Scheduled token refresh - invalidating current session");
        refreshVaultSession();
    }
    
    /**
     * Force session refresh by invalidating current session
     */
    private synchronized void refreshVaultSession() {
        if (sessionManager != null) {
            try {
                logger.info("Invalidating Vault session to force re-authentication...");
                
                // This will force the SessionManager to re-authenticate on next request
                sessionManager.destroy();
                
                lastRefresh = Instant.now();
                logger.info("Vault session invalidated at {}. Next request will trigger fresh AppRole login.", lastRefresh);
                
            } catch (Exception e) {
                logger.error("Error during session refresh", e);
            }
        }
    }
    
    /**
     * Manual refresh method (useful for health checks or manual triggers)
     */
    public void forceRefresh() {
        logger.info("Manual session refresh requested");
        refreshVaultSession();
    }
    
    /**
     * Check if we need to refresh based on time
     */
    public boolean shouldRefresh() {
        if (lastRefresh == null) return true;
        return Duration.between(lastRefresh, Instant.now()).toMinutes() >= 15;
    }
    
    /**
     * Get minutes since last refresh
     */
    public long getMinutesSinceRefresh() {
        if (lastRefresh == null) return -1;
        return Duration.between(lastRefresh, Instant.now()).toMinutes();
    }
    
    /**
     * Get current session status
     */
    public boolean hasActiveSession() {
        return sessionManager != null && sessionManager.getSessionToken() != null;
    }
    
    /**
     * Get last refresh time
     */
    public Instant getLastRefreshTime() {
        return lastRefresh;
    }
}