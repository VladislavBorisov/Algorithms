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
public class CustomVaultTokenManager {
    
    private static final Logger logger = LoggerFactory.getLogger(CustomVaultTokenManager.class);
    
    @Value("${vault.uri}")
    private String vaultUri;
    
    @Value("${vault.role-id}")
    private String roleId;
    
    @Value("${vault.secret-id}")
    private String secretId;
    
    private String currentToken;
    private Instant tokenCreatedAt;
    private final Object tokenLock = new Object();
    private final RestTemplate restTemplate = new RestTemplate();
    
    @PostConstruct
    public void initialize() {
        logger.info("Initializing Custom Vault Token Manager");
        authenticateWithVault();
    }
    
    @Scheduled(fixedRate = 15, timeUnit = TimeUnit.MINUTES)
    public void refreshToken() {
        logger.info("Scheduled token refresh triggered");
        authenticateWithVault();
    }
    
    private void authenticateWithVault() {
        synchronized (tokenLock) {
            try {
                logger.info("Authenticating with Vault using AppRole...");
                
                // Prepare AppRole login request
                Map<String, String> loginData = Map.of(
                    "role_id", roleId,
                    "secret_id", secretId
                );
                
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                
                HttpEntity<Map<String, String>> request = new HttpEntity<>(loginData, headers);
                
                // Make AppRole login request
                String loginUrl = vaultUri + "/v1/auth/approle/login";
                ResponseEntity<Map> response = restTemplate.postForEntity(loginUrl, request, Map.class);
                
                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> auth = (Map<String, Object>) response.getBody().get("auth");
                    
                    if (auth != null) {
                        this.currentToken = (String) auth.get("client_token");
                        this.tokenCreatedAt = Instant.now();
                        
                        logger.info("Successfully obtained new Vault token. Token created at: {}", tokenCreatedAt);
                        
                        // Log token type for debugging
                        String tokenType = (String) auth.get("token_type");
                        Integer leaseDuration = (Integer) auth.get("lease_duration");
                        logger.info("Token type: {}, Lease duration: {} seconds", tokenType, leaseDuration);
                    } else {
                        logger.error("Authentication response missing 'auth' section");
                    }
                } else {
                    logger.error("Failed to authenticate with Vault. Status: {}", response.getStatusCode());
                }
                
            } catch (Exception e) {
                logger.error("Error during Vault authentication", e);
                // Don't clear current token on failure - keep using it until it expires
            }
        }
    }
    
    public String getValidToken() {
        synchronized (tokenLock) {
            // Check if we need to refresh the token
            if (shouldRefreshToken()) {
                logger.warn("Token is expired or close to expiration, refreshing...");
                authenticateWithVault();
            }
            return currentToken;
        }
    }
    
    private boolean shouldRefreshToken() {
        if (currentToken == null || tokenCreatedAt == null) {
            return true;
        }
        
        long minutesOld = Duration.between(tokenCreatedAt, Instant.now()).toMinutes();
        // Refresh if token is 18+ minutes old (2 minutes before 20-minute expiry)
        return minutesOld >= 18;
    }
    
    public boolean isTokenValid() {
        synchronized (tokenLock) {
            if (currentToken == null || tokenCreatedAt == null) {
                return false;
            }
            
            long minutesOld = Duration.between(tokenCreatedAt, Instant.now()).toMinutes();
            return minutesOld < 18; // Consider valid if less than 18 minutes old
        }
    }
    
    public long getTokenAgeMinutes() {
        synchronized (tokenLock) {
            if (tokenCreatedAt == null) {
                return -1;
            }
            return Duration.between(tokenCreatedAt, Instant.now()).toMinutes();
        }
    }
    
    public Instant getTokenCreatedAt() {
        synchronized (tokenLock) {
            return tokenCreatedAt;
        }
    }
    
    // Method to force immediate token refresh (useful for testing)
    public void forceRefresh() {
        logger.info("Force token refresh requested");
        authenticateWithVault();
    }
}