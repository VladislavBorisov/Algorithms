package com.example.controller;

import com.example.config.BatchTokenVaultManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

@RestController
public class VaultManagementController {

    private static final Logger logger = LoggerFactory.getLogger(VaultManagementController.class);

    @Autowired
    private BatchTokenVaultManager vaultManager;

    @PostMapping("/management/vault/reauth")
    public ResponseEntity<Map<String, Object>> forceReAuthentication() {
        Map<String, Object> response = new HashMap<>();
        try {
            logger.info("Manual re-authentication requested via API");
            vaultManager.forceReAuthentication();
            response.put("status", "success");
            response.put("message", "Re-authentication triggered");
            response.put("authenticated", vaultManager.isAuthenticated());
            response.put("token_age_minutes", vaultManager.getTokenAgeMinutes());
            response.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Manual re-authentication failed", e);
            response.put("status", "error");
            response.put("message", "Re-authentication failed: " + e.getMessage());
            response.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.status(500).body(response);
        }
    }

    @GetMapping("/management/vault/status")
    public ResponseEntity<Map<String, Object>> getVaultStatus() {
        Map<String, Object> response = new HashMap<>();
        try {
            response.put("authenticated", vaultManager.isAuthenticated());
            response.put("token_age_minutes", vaultManager.getTokenAgeMinutes());
            response.put("token_created_at", vaultManager.getTokenCreatedAt());
            response.put("max_ttl_minutes", 20);
            response.put("reauth_interval_minutes", 15);
            response.put("next_reauth_in_minutes", 15 - (vaultManager.getTokenAgeMinutes() % 15));
            response.put("timestamp", System.currentTimeMillis());
            
            // Add status indicator
            long tokenAge = vaultManager.getTokenAgeMinutes();
            if (tokenAge >= 18) {
                response.put("token_status", "CRITICAL - Expires soon");
            } else if (tokenAge >= 15) {
                response.put("token_status", "WARNING - Aging");
            } else {
                response.put("token_status", "HEALTHY");
            }
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to get vault status", e);
            response.put("error", e.getMessage());
            response.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.status(500).body(response);
        }
    }

    @GetMapping("/management/vault/health")
    public ResponseEntity<Map<String, Object>> getVaultHealth() {
        Map<String, Object> response = new HashMap<>();
        try {
            if (vaultManager.getVaultTemplate() != null) {
                vaultManager.getVaultTemplate().opsForSys().health();
                response.put("vault_connectivity", "UP");
            } else {
                response.put("vault_connectivity", "DOWN - No VaultTemplate");
            }
            
            response.put("authenticated", vaultManager.isAuthenticated());
            response.put("token_age_minutes", vaultManager.getTokenAgeMinutes());
            response.put("timestamp", System.currentTimeMillis());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.warn("Vault health check failed", e);
            response.put("vault_connectivity", "DOWN");
            response.put("error", e.getMessage());
            response.put("authenticated", vaultManager.isAuthenticated());
            response.put("timestamp", System.currentTimeMillis());
            return ResponseEntity.status(503).body(response);
        }
    }
}