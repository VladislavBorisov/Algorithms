package com.example.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.config.server.environment.VaultEnvironmentRepository;
import org.springframework.cloud.config.server.environment.VaultEnvironmentRepositoryFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.ConfigurableEnvironment;
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
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Configuration
public class VaultEnvironmentRepositoryConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(VaultEnvironmentRepositoryConfig.class);
    
    @Value("${spring.cloud.config.server.vault.uri}")
    private String vaultUri;
    
    @Value("${spring.cloud.config.server.vault.app-role.role-id}")
    private String roleId;
    
    @Value("${spring.cloud.config.server.vault.app-role.secret-id}")
    private String secretId;
    
    @Value("${spring.cloud.config.server.vault.backend:secret}")
    private String backend;
    
    @Value("${spring.cloud.config.server.vault.default-key:application}")
    private String defaultKey;
    
    @Value("${spring.cloud.config.server.vault.profile-separator:/}")
    private String profileSeparator;
    
    private SessionManager sessionManager;
    private VaultTemplate vaultTemplate;
    private Instant lastRefresh;
    
    @PostConstruct
    public void initialize() {
        logger.info("Initializing custom Vault Environment Repository with token refresh");
        createVaultTemplate();
    }
    
    private void createVaultTemplate() {
        VaultEndpoint endpoint = VaultEndpoint.from(URI.create(vaultUri));
        
        AppRoleAuthenticationOptions options = AppRoleAuthenticationOptions.builder()
            .roleId(AppRoleAuthenticationOptions.RoleId.provided(roleId))
            .secretId(AppRoleAuthenticationOptions.SecretId.provided(secretId))
            .build();
            
        AppRoleAuthentication authentication = new AppRoleAuthentication(options, new RestTemplate());
        this.sessionManager = new SimpleSessionManager(authentication);
        this.vaultTemplate = new VaultTemplate(endpoint, sessionManager);
        
        logger.info("Created VaultTemplate with AppRole authentication");
    }
    
    @Bean
    @Primary
    public VaultEnvironmentRepository vaultEnvironmentRepository(ConfigurableEnvironment environment) {
        VaultEnvironmentRepositoryFactory.VaultEnvironmentRepositoryBuilder builder = 
            new VaultEnvironmentRepositoryFactory.VaultEnvironmentRepositoryBuilder()
                .vaultTemplate(vaultTemplate)
                .backend(backend)
                .defaultKey(defaultKey)
                .profileSeparator(profileSeparator);
        
        VaultEnvironmentRepository repository = builder.build();
        logger.info("Created custom VaultEnvironmentRepository with refreshable tokens");
        return repository;
    }
    
    @Scheduled(fixedRate = 15, timeUnit = TimeUnit.MINUTES)
    public void refreshVaultSession() {
        logger.info("Refreshing Vault session for batch token expiration");
        
        try {
            if (sessionManager != null) {
                // Invalidate current session to force re-authentication
                sessionManager.destroy();
                lastRefresh = Instant.now();
                logger.info("Vault session invalidated at {}. Next request will get fresh token.", lastRefresh);
            }
        } catch (Exception e) {
            logger.error("Error refreshing Vault session", e);
        }
    }
    
    public Instant getLastRefreshTime() {
        return lastRefresh;
    }
    
    public boolean hasActiveSession() {
        return sessionManager != null && sessionManager.getSessionToken() != null;
    }
}