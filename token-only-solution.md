# Simple Token-Only Solution for Batch Tokens

## The Problem
You only need to solve the token expiration issue, not replace the entire Vault integration.

## Simple Solution: Token Injector

Instead of replacing Spring's Vault integration, just inject fresh tokens into it.

### 1. Simple Token Manager (Only)
```java
@Component
public class VaultTokenRefresher {
    
    private static final Logger logger = LoggerFactory.getLogger(VaultTokenRefresher.class);
    
    @Value("${vault.uri}")
    private String vaultUri;
    
    @Value("${vault.app-role.role-id}")
    private String roleId;
    
    @Value("${vault.app-role.secret-id}")
    private String secretId;
    
    private String currentToken;
    private final RestTemplate restTemplate = new RestTemplate();
    
    @PostConstruct
    public void initialize() {
        refreshToken();
    }
    
    @Scheduled(fixedRate = 15, timeUnit = TimeUnit.MINUTES)
    public void refreshToken() {
        try {
            logger.info("Refreshing Vault token...");
            
            Map<String, String> loginData = Map.of(
                "role_id", roleId,
                "secret_id", secretId
            );
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> request = new HttpEntity<>(loginData, headers);
            
            ResponseEntity<Map> response = restTemplate.postForEntity(
                vaultUri + "/v1/auth/approle/login", 
                request, 
                Map.class
            );
            
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> auth = (Map<String, Object>) response.getBody().get("auth");
                if (auth != null) {
                    this.currentToken = (String) auth.get("client_token");
                    
                    // Set the token in system properties so Spring picks it up
                    System.setProperty("spring.cloud.vault.token", this.currentToken);
                    
                    logger.info("Vault token refreshed successfully");
                }
            }
        } catch (Exception e) {
            logger.error("Failed to refresh Vault token", e);
        }
    }
    
    public String getCurrentToken() {
        return currentToken;
    }
}
```

### 2. Application Configuration (Keep Default Vault Integration)
```yaml
spring:
  application:
    name: config-server
  cloud:
    config:
      server:
        composite:
          - type: vault
            uri: ${VAULT_URI:http://localhost:8200}
            backend: secret
            default-key: application
            profile-separator: '/'
            order: 1
          - type: s3
            uri: ${S3_URI}
            bucket: ${S3_BUCKET}
            order: 2
        vault:
          authentication: TOKEN  # Use token instead of APPROLE
          token: ${spring.cloud.vault.token:}  # Will be set by our refresher
          uri: ${VAULT_URI:http://localhost:8200}

# Our token refresher configuration
vault:
  uri: ${VAULT_URI:http://localhost:8200}
  app-role:
    role-id: ${VAULT_ROLE_ID}
    secret-id: ${VAULT_SECRET_ID}
```

## Even Simpler: Override VaultTemplate Bean

### Option 2: Custom VaultTemplate with Token Refresh
```java
@Configuration
public class VaultTokenConfiguration {
    
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
        refreshToken();
    }
    
    @Scheduled(fixedRate = 15, timeUnit = TimeUnit.MINUTES)
    public void refreshToken() {
        // Same token refresh logic as above
        // ...
    }
    
    @Bean
    @Primary
    public VaultTemplate vaultTemplate() {
        VaultEndpoint endpoint = VaultEndpoint.from(URI.create(vaultUri));
        
        // Create VaultTemplate with dynamic token supplier
        return new VaultTemplate(endpoint, () -> VaultToken.of(getCurrentValidToken()));
    }
    
    private String getCurrentValidToken() {
        // Check if token needs refresh
        if (currentToken == null || isTokenOld()) {
            refreshToken();
        }
        return currentToken;
    }
    
    private boolean isTokenOld() {
        if (tokenCreatedAt == null) return true;
        return Duration.between(tokenCreatedAt, Instant.now()).toMinutes() >= 18;
    }
}
```

## Why This is Better

✅ **Minimal changes** - Keep using Spring's Vault integration  
✅ **Only token management** - Don't reinvent the wheel  
✅ **Drop-in solution** - Works with existing configuration  
✅ **Less code** - Much simpler than custom repository  

## Which Approach to Use?

1. **If you want minimal changes**: Use the token refresher with system properties
2. **If you want more control**: Use the custom VaultTemplate bean
3. **If you need complete control**: Use the full custom repository (previous solution)

The token-only approach is much simpler and achieves the same goal!