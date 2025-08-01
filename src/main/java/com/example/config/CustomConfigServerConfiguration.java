package com.example.config;

import com.example.vault.CustomVaultEnvironmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.config.server.EnableConfigServer;
import org.springframework.cloud.config.server.environment.CompositeEnvironmentRepository;
import org.springframework.cloud.config.server.environment.EnvironmentRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableConfigServer
public class CustomConfigServerConfiguration {
    
    @Autowired
    private CustomVaultEnvironmentRepository customVaultRepository;
    
    @Bean
    @Primary
    public CompositeEnvironmentRepository environmentRepository() {
        List<EnvironmentRepository> repositories = new ArrayList<>();
        
        // Add our custom Vault repository first (highest priority)
        repositories.add(customVaultRepository);
        
        // You can add other repositories here as fallbacks
        // repositories.add(s3EnvironmentRepository());
        // repositories.add(gitEnvironmentRepository());
        
        return new CompositeEnvironmentRepository(repositories);
    }
}