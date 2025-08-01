# Custom Vault Token Management for Spring Cloud Config Server

## The Idea
Instead of relying on Spring's built-in Vault integration, implement your own token management logic that:
1. Handles AppRole login manually
2. Manages token lifecycle (refresh before expiration)
3. Overrides Spring's Vault backend with your custom implementation

## Solution: Custom Vault Environment Repository

### 1. Custom Vault Token Manager
```java
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
    
    @Scheduled(fixedRate = 15, timeUnit = TimeUnit.MINUTES)
    public void refreshToken() {
        logger.info("Refreshing Vault token...");
        authenticateWithVault();
    }
    
    @PostConstruct
    public void initialize() {
        authenticateWithVault();
    }
    
    private void authenticateWithVault() {
        synchronized (tokenLock) {
            try {
                // Manual AppRole login
                Map<String, String> loginData = Map.of(
                    "role_id", roleId,
                    "secret_id", secretId
                );
                
                RestTemplate restTemplate = new RestTemplate();
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
                    this.currentToken = (String) auth.get("client_token");
                    this.tokenCreatedAt = Instant.now();
                    
                    logger.info("Successfully obtained new Vault token");
                } else {
                    logger.error("Failed to authenticate with Vault");
                }
                
            } catch (Exception e) {
                logger.error("Error during Vault authentication", e);
            }
        }
    }
    
    public String getValidToken() {
        synchronized (tokenLock) {
            // Check if token is close to expiration (18+ minutes old)
            if (tokenCreatedAt == null || 
                Duration.between(tokenCreatedAt, Instant.now()).toMinutes() >= 18) {
                logger.warn("Token is expired or close to expiration, refreshing...");
                authenticateWithVault();
            }
            return currentToken;
        }
    }
    
    public boolean isTokenValid() {
        return currentToken != null && 
               tokenCreatedAt != null && 
               Duration.between(tokenCreatedAt, Instant.now()).toMinutes() < 18;
    }
}
```

### 2. Custom Vault Environment Repository
```java
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
        Environment environment = new Environment(application, profile, label);
        
        try {
            // Get valid token
            String token = tokenManager.getValidToken();
            if (token == null) {
                logger.error("No valid Vault token available");
                return environment;
            }
            
            // Fetch secrets from Vault
            Map<String, Object> secrets = fetchSecretsFromVault(token, application, profile);
            
            if (!secrets.isEmpty()) {
                PropertySource propertySource = new PropertySource(
                    "vault:" + application + "/" + profile,
                    secrets
                );
                environment.add(propertySource);
            }
            
        } catch (Exception e) {
            logger.error("Error fetching configuration from Vault", e);
        }
        
        return environment;
    }
    
    private Map<String, Object> fetchSecretsFromVault(String token, String application, String profile) {
        Map<String, Object> allSecrets = new HashMap<>();
        
        try {
            // Try different secret paths
            String[] paths = {
                "secret/data/" + application,
                "secret/data/" + application + "/" + profile,
                "secret/data/application"
            };
            
            for (String path : paths) {
                try {
                    Map<String, Object> secrets = fetchSecretFromPath(token, path);
                    allSecrets.putAll(secrets);
                } catch (Exception e) {
                    logger.debug("No secrets found at path: " + path);
                }
            }
            
        } catch (Exception e) {
            logger.error("Error fetching secrets from Vault", e);
        }
        
        return allSecrets;
    }
    
    private Map<String, Object> fetchSecretFromPath(String token, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Vault-Token", token);
        
        HttpEntity<Void> request = new HttpEntity<>(headers);
        
        ResponseEntity<Map> response = restTemplate.exchange(
            vaultUri + "/v1/" + path,
            HttpMethod.GET,
            request,
            Map.class
        );
        
        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
            Map<String, Object> responseBody = response.getBody();
            Map<String, Object> data = (Map<String, Object>) responseBody.get("data");
            if (data != null) {
                Map<String, Object> secrets = (Map<String, Object>) data.get("data");
                return secrets != null ? secrets : new HashMap<>();
            }
        }
        
        return new HashMap<>();
    }
}
```

### 3. Configuration to Override Default Vault Backend
```java
@Configuration
@EnableConfigServer
public class ConfigServerConfiguration {
    
    @Autowired
    private CustomVaultEnvironmentRepository customVaultRepo;
    
    @Bean
    @Primary
    public CompositeEnvironmentRepository environmentRepository() {
        List<EnvironmentRepository> repositories = new ArrayList<>();
        
        // Add custom Vault repository first
        repositories.add(customVaultRepo);
        
        // Add S3 fallback
        // repositories.add(s3EnvironmentRepository());
        
        return new CompositeEnvironmentRepository(repositories);
    }
}
```

### 4. Application Configuration
```yaml
server:
  port: 8888

spring:
  application:
    name: config-server

# Custom Vault settings
vault:
  uri: ${VAULT_URI:http://localhost:8200}
  role-id: ${VAULT_ROLE_ID}
  secret-id: ${VAULT_SECRET_ID}

# Disable default Vault integration
spring:
  cloud:
    config:
      server:
        # Don't configure vault here - we handle it manually
        composite: []

management:
  endpoints:
    web:
      exposure:
        include: health,refresh
```

### 5. Health Indicator for Custom Vault
```java
@Component("customVault")
public class CustomVaultHealthIndicator implements HealthIndicator {
    
    @Autowired
    private CustomVaultTokenManager tokenManager;
    
    @Override
    public Health health() {
        try {
            if (tokenManager.isTokenValid()) {
                long tokenAge = Duration.between(
                    tokenManager.getTokenCreatedAt(), 
                    Instant.now()
                ).toMinutes();
                
                return Health.up()
                    .withDetail("status", "Connected")
                    .withDetail("token_age_minutes", tokenAge)
                    .withDetail("token_valid", true)
                    .build();
            } else {
                return Health.down()
                    .withDetail("status", "No valid token")
                    .withDetail("token_valid", false)
                    .build();
            }
        } catch (Exception e) {
            return Health.down()
                .withDetail("error", e.getMessage())
                .build();
        }
    }
}
```

## How This Works

1. **CustomVaultTokenManager** handles all token lifecycle:
   - Logs in with AppRole every 15 minutes
   - Provides valid tokens on demand
   - Checks token age before returning

2. **CustomVaultEnvironmentRepository** replaces Spring's Vault backend:
   - Uses your custom token manager
   - Fetches secrets directly from Vault API
   - Returns configuration as Environment objects

3. **No dependency on Spring Vault libraries** - just REST calls

4. **Automatic token refresh** - scheduled every 15 minutes

5. **Fallback handling** - can add S3 or other backends

## Benefits

✅ **Full control** over token lifecycle  
✅ **Works with batch tokens** - refreshes before expiration  
✅ **No restart required** - handles tokens in application logic  
✅ **Integrates with Config Server** - looks like normal backend  
✅ **Monitoring included** - health indicators and logging  

## Testing

```bash
# Start config server
mvn spring-boot:run

# Test configuration retrieval
curl http://localhost:8888/myapp/default

# Check health
curl http://localhost:8888/actuator/health/customVault

# Watch logs for token refresh
tail -f logs/application.log | grep -i vault
```

This approach gives you complete control over the Vault integration while still working within the Spring Cloud Config Server framework.