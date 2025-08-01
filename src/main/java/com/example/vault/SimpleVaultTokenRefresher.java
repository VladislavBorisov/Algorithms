package com.example.vault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class SimpleVaultTokenRefresher {
    
    private static final Logger logger = LoggerFactory.getLogger(SimpleVaultTokenRefresher.class);
    
    @Value("${vault.uri}")
    private String vaultUri;
    
    @Value("${vault.app-role.role-id}")
    private String roleId;
    
    @Value("${vault.app-role.secret-id}")
    private String secretId;
    
    private String currentToken;
    private Instant tokenCreatedAt;
    private final RestTemplate restTemplate = new RestTemplate();
    
    @PostConstruct
    public void initialize() {
        logger.info("Initializing Vault Token Refresher");
        refreshToken();
    }
    
    @Scheduled(fixedRate = 15, timeUnit = TimeUnit.MINUTES)
    public void scheduledRefresh() {
        logger.info("Scheduled token refresh started");
        refreshToken();
    }
    
    private synchronized void refreshToken() {
        try {
            logger.info("Refreshing Vault token using AppRole...");
            
            // Prepare AppRole login request
            Map<String, String> loginData = Map.of(
                "role_id", roleId,
                "secret_id", secretId
            );
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> request = new HttpEntity<>(loginData, headers);
            
            // Login to get new token
            ResponseEntity<Map> response = restTemplate.postForEntity(
                vaultUri + "/v1/auth/approle/login", 
                request, 
                Map.class
            );
            
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> auth = (Map<String, Object>) response.getBody().get("auth");
                
                if (auth != null) {
                    String newToken = (String) auth.get("client_token");
                    
                    if (newToken != null) {
                        this.currentToken = newToken;
                        this.tokenCreatedAt = Instant.now();
                        
                        // Update system property so Spring Config Server picks up the new token
                        System.setProperty("spring.cloud.config.server.vault.token", newToken);
                        
                        logger.info("Vault token refreshed successfully at {}", tokenCreatedAt);
                        
                        // Log token info for debugging
                        String tokenType = (String) auth.get("token_type");
                        Integer leaseDuration = (Integer) auth.get("lease_duration");
                        logger.debug("Token type: {}, Lease duration: {} seconds", tokenType, leaseDuration);
                    }
                } else {
                    logger.error("Authentication response missing 'auth' section");
                }
            } else {
                logger.error("Failed to authenticate with Vault. HTTP Status: {}", response.getStatusCode());
            }
            
        } catch (Exception e) {
            logger.error("Error refreshing Vault token", e);
        }
    }
    
    public String getCurrentToken() {
        return currentToken;
    }
    
    public long getTokenAgeMinutes() {
        if (tokenCreatedAt == null) {
            return -1;
        }
        return Duration.between(tokenCreatedAt, Instant.now()).toMinutes();
    }
    
    public boolean isTokenValid() {
        return currentToken != null && 
               tokenCreatedAt != null && 
               Duration.between(tokenCreatedAt, Instant.now()).toMinutes() < 18;
    }
    
    // Manual refresh method (useful for testing)
    public void forceRefresh() {
        logger.info("Manual token refresh requested");
        refreshToken();
    }
}