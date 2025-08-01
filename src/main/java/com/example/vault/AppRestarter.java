package com.example.vault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
public class AppRestarter {
    
    private static final Logger logger = LoggerFactory.getLogger(AppRestarter.class);
    
    @Scheduled(fixedRate = 15, timeUnit = TimeUnit.MINUTES)
    public void restartApp() {
        logger.info("Restarting application to get fresh Vault batch tokens");
        
        try {
            // Log the restart
            Instant restartTime = Instant.now();
            logger.info("Application restart triggered at {} to refresh Vault tokens", restartTime);
            
            // Exit the application - container/process manager will restart it
            System.exit(0);
            
        } catch (Exception e) {
            logger.error("Error during application restart", e);
        }
    }
}