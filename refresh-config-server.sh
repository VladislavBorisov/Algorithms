#!/bin/bash

# Simple script to refresh Config Server tokens
# Run this every 15 minutes to prevent batch token expiration

CONFIG_SERVER_URL=${CONFIG_SERVER_URL:-http://localhost:8888}
LOG_FILE=${LOG_FILE:-config-refresh.log}

# Function to log with timestamp
log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') - $1" | tee -a "$LOG_FILE"
}

# Function to refresh config server
refresh_config() {
    log "Attempting to refresh config server..."
    
    response=$(curl -s -w "HTTPSTATUS:%{http_code}" -X POST "$CONFIG_SERVER_URL/actuator/refresh")
    http_code=$(echo "$response" | tr -d '\n' | sed -e 's/.*HTTPSTATUS://')
    body=$(echo "$response" | sed -e 's/HTTPSTATUS:.*//g')
    
    if [ "$http_code" -eq 200 ]; then
        log "SUCCESS: Config server refreshed successfully"
        log "Response: $body"
    else
        log "ERROR: Refresh failed with HTTP code: $http_code"
        log "Response: $body"
        exit 1
    fi
}

# Test connectivity first
test_connectivity() {
    log "Testing connectivity to config server..."
    
    response=$(curl -s -w "HTTPSTATUS:%{http_code}" "$CONFIG_SERVER_URL/actuator/health")
    http_code=$(echo "$response" | tr -d '\n' | sed -e 's/.*HTTPSTATUS://')
    
    if [ "$http_code" -eq 200 ]; then
        log "Config server is reachable"
        return 0
    else
        log "ERROR: Config server not reachable (HTTP: $http_code)"
        return 1
    fi
}

# Main execution
main() {
    log "Starting config server refresh process"
    
    if test_connectivity; then
        refresh_config
        log "Refresh process completed successfully"
    else
        log "Skipping refresh due to connectivity issues"
        exit 1
    fi
}

# Run main function
main