package com.example.vault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultToken;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Configuration
public class VaultAppRoleTokenInjector {
    
    private static final Logger logger = LoggerFactory.getLogger(VaultAppRoleTokenInjector.class);
    
    @Value("${spring.cloud.config.server.vault.uri}")
    private String vaultUri;
    
    @Value("${spring.cloud.config.server.vault.app-role.role-id}")
    private String roleId;
    
    @Value("${spring.cloud.config.server.vault.app-role.secret-id}")
    private String secretId;
    
    private String currentToken;
    private Instant tokenCreatedAt;
    private final RestTemplate restTemplate = new RestTemplate();
    
    @PostConstruct
    public void initialize() {
        logger.info("Initializing Vault AppRole Token Injector");
        refreshAppRoleToken();
    }
    
    @Scheduled(fixedRate = 15, timeUnit = TimeUnit.MINUTES)
    public void scheduledTokenRefresh() {
        logger.info("Scheduled AppRole token refresh");
        refreshAppRoleToken();
    }
    
    /**
     * Override Spring's default VaultTemplate with one that uses our fresh tokens
     */
    @Bean
    @Primary
    public VaultTemplate vaultTemplate() {
        VaultEndpoint endpoint = VaultEndpoint.from(URI.create(vaultUri));
        
        // Create VaultTemplate with our token supplier
        return new VaultTemplate(endpoint, this::getFreshToken);
    }
    
    private VaultToken getFreshToken() {
        // Check if token needs refresh before returning
        if (shouldRefreshToken()) {
            logger.debug("Token needs refresh, getting fresh one...");
            refreshAppRoleToken();
        }
        
        return VaultToken.of(currentToken);
    }
    
    private synchronized void refreshAppRoleToken() {
        try {
            logger.info("Refreshing token using AppRole...");
            
            // AppRole login request
            Map<String, String> loginData = Map.of(
                "role_id", roleId,
                "secret_id", secretId
            );
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> request = new HttpEntity<>(loginData, headers);
            
            String loginUrl = vaultUri + "/v1/auth/approle/login";
            ResponseEntity<Map> response = restTemplate.postForEntity(loginUrl, request, Map.class);
            
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> auth = (Map<String, Object>) response.getBody().get("auth");
                
                if (auth != null) {
                    String newToken = (String) auth.get("client_token");
                    String tokenType = (String) auth.get("token_type");
                    Integer leaseDuration = (Integer) auth.get("lease_duration");
                    
                    if (newToken != null) {
                        this.currentToken = newToken;
                        this.tokenCreatedAt = Instant.now();
                        
                        logger.info("AppRole token refreshed! Type: {}, Duration: {}s, Created: {}", 
                                  tokenType, leaseDuration, tokenCreatedAt);
                    } else {
                        logger.error("Received null token from AppRole login");
                    }
                } else {
                    logger.error("No auth section in AppRole response");
                }
            } else {
                logger.error("AppRole login failed. HTTP: {}", response.getStatusCode());
            }
            
        } catch (Exception e) {
            logger.error("Error during AppRole token refresh", e);
            // Keep using current token if refresh fails
        }
    }
    
    private boolean shouldRefreshToken() {
        if (currentToken == null || tokenCreatedAt == null) {
            return true;
        }
        
        long minutes = Duration.between(tokenCreatedAt, Instant.now()).toMinutes();
        return minutes >= 18; // Refresh if 18+ minutes old
    }
    
    // Getters for monitoring
    public String getCurrentToken() {
        return currentToken;
    }
    
    public long getTokenAgeMinutes() {
        if (tokenCreatedAt == null) return -1;
        return Duration.between(tokenCreatedAt, Instant.now()).toMinutes();
    }
    
    public Instant getTokenCreatedAt() {
        return tokenCreatedAt;
    }
    
    public boolean isTokenValid() {
        return currentToken != null && 
               tokenCreatedAt != null && 
               Duration.between(tokenCreatedAt, Instant.now()).toMinutes() < 18;
    }
}