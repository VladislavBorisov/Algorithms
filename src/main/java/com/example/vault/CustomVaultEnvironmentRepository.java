package com.example.vault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.config.environment.Environment;
import org.springframework.cloud.config.environment.PropertySource;
import org.springframework.cloud.config.server.environment.EnvironmentRepository;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Component
public class CustomVaultEnvironmentRepository implements EnvironmentRepository {
    
    private static final Logger logger = LoggerFactory.getLogger(CustomVaultEnvironmentRepository.class);
    
    @Autowired
    private CustomVaultTokenManager tokenManager;
    
    @Value("${vault.uri}")
    private String vaultUri;
    
    private final RestTemplate restTemplate = new RestTemplate();
    
    @Override
    public Environment findOne(String application, String profile, String label) {
        logger.debug("Fetching configuration for application: {}, profile: {}, label: {}", 
                    application, profile, label);
        
        Environment environment = new Environment(application, profile, label);
        
        try {
            // Get valid token from our token manager
            String token = tokenManager.getValidToken();
            if (token == null) {
                logger.error("No valid Vault token available");
                return environment;
            }
            
            // Fetch secrets from Vault using multiple path strategies
            Map<String, Object> allSecrets = fetchSecretsFromVault(token, application, profile);
            
            if (!allSecrets.isEmpty()) {
                PropertySource propertySource = new PropertySource(
                    "vault:" + application + "/" + profile,
                    allSecrets
                );
                environment.add(propertySource);
                logger.info("Added {} properties from Vault for {}/{}", 
                           allSecrets.size(), application, profile);
            } else {
                logger.warn("No secrets found in Vault for application: {}, profile: {}", 
                           application, profile);
            }
            
        } catch (Exception e) {
            logger.error("Error fetching configuration from Vault for {}/{}", application, profile, e);
        }
        
        return environment;
    }
    
    private Map<String, Object> fetchSecretsFromVault(String token, String application, String profile) {
        Map<String, Object> allSecrets = new HashMap<>();
        
        // Define multiple paths to try (in order of preference)
        String[] paths = {
            "secret/data/" + application + "/" + profile,  // Specific app/profile
            "secret/data/" + application,                  // Application-wide
            "secret/data/application/" + profile,          // Global profile
            "secret/data/application"                      // Global application
        };
        
        for (String path : paths) {
            try {
                Map<String, Object> secrets = fetchSecretFromPath(token, path);
                if (!secrets.isEmpty()) {
                    allSecrets.putAll(secrets);
                    logger.debug("Found {} secrets at path: {}", secrets.size(), path);
                }
            } catch (Exception e) {
                logger.debug("No secrets found at path: {} ({})", path, e.getMessage());
            }
        }
        
        return allSecrets;
    }
    
    private Map<String, Object> fetchSecretFromPath(String token, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Vault-Token", token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        HttpEntity<Void> request = new HttpEntity<>(headers);
        
        String secretUrl = vaultUri + "/v1/" + path;
        logger.debug("Fetching secrets from: {}", secretUrl);
        
        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                secretUrl,
                HttpMethod.GET,
                request,
                Map.class
            );
            
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return extractSecretsFromResponse(response.getBody());
            } else {
                logger.debug("No data found at path: {} (HTTP {})", path, response.getStatusCode());
            }
        } catch (Exception e) {
            logger.debug("Failed to fetch from path: {} - {}", path, e.getMessage());
        }
        
        return new HashMap<>();
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractSecretsFromResponse(Map<String, Object> responseBody) {
        try {
            // Vault KV v2 structure: response.data.data contains the actual secrets
            Map<String, Object> data = (Map<String, Object>) responseBody.get("data");
            if (data != null) {
                Map<String, Object> secrets = (Map<String, Object>) data.get("data");
                if (secrets != null) {
                    logger.debug("Extracted {} secrets from Vault response", secrets.size());
                    return secrets;
                }
            }
            
            // Try KV v1 structure: response.data contains the secrets directly
            if (responseBody.containsKey("data")) {
                Map<String, Object> directData = (Map<String, Object>) responseBody.get("data");
                if (directData != null && !directData.containsKey("data")) {
                    // This looks like KV v1 format
                    logger.debug("Extracted {} secrets from Vault response (KV v1)", directData.size());
                    return directData;
                }
            }
            
        } catch (ClassCastException e) {
            logger.warn("Unexpected response format from Vault", e);
        }
        
        return new HashMap<>();
    }
}