package com.example.vault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
public class SimpleAppRoleRefresher {
    
    private static final Logger logger = LoggerFactory.getLogger(SimpleAppRoleRefresher.class);
    
    @Value("${server.port:8888}")
    private int serverPort;
    
    private final RestTemplate restTemplate = new RestTemplate();
    private Instant lastRefresh;
    
    @Scheduled(fixedRate = 15, timeUnit = TimeUnit.MINUTES)
    public void refreshConfigServer() {
        logger.info("Triggering Config Server refresh to handle batch token expiration");
        
        try {
            String refreshUrl = "http://localhost:" + serverPort + "/actuator/refresh";
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> request = new HttpEntity<>("{}", headers);
            
            ResponseEntity<String> response = restTemplate.postForEntity(refreshUrl, request, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                lastRefresh = Instant.now();
                logger.info("Config Server refresh completed successfully at {}", lastRefresh);
            } else {
                logger.warn("Config Server refresh returned status: {}", response.getStatusCode());
            }
            
        } catch (Exception e) {
            logger.error("Error triggering Config Server refresh", e);
        }
    }
    
    public Instant getLastRefreshTime() {
        return lastRefresh;
    }
}