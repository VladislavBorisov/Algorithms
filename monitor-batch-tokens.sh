#!/bin/bash

# Monitoring Script for Batch Token Spring Cloud Config Server
# This script monitors token age and triggers re-authentication when needed

CONFIG_SERVER_URL=${CONFIG_SERVER_URL:-http://localhost:8888}
CHECK_INTERVAL=${CHECK_INTERVAL:-60}  # Check every 60 seconds
LOG_FILE=${LOG_FILE:-batch-token-monitor.log}

# Colors for output
RED='\033[0;31m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
NC='\033[0m' # No Color

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') - $1" | tee -a "$LOG_FILE"
}

check_vault_status() {
    local response=$(curl -s -w "%{http_code}" "$CONFIG_SERVER_URL/management/vault/status" -o /tmp/vault_status.json)
    local http_code=${response: -3}
    
    if [ "$http_code" = "200" ]; then
        local token_age=$(jq -r '.token_age_minutes' /tmp/vault_status.json 2>/dev/null || echo "-1")
        local authenticated=$(jq -r '.authenticated' /tmp/vault_status.json 2>/dev/null || echo "false")
        local token_status=$(jq -r '.token_status' /tmp/vault_status.json 2>/dev/null || echo "UNKNOWN")
        local created_at=$(jq -r '.token_created_at' /tmp/vault_status.json 2>/dev/null || echo "unknown")
        
        case "$token_status" in
            "CRITICAL"*)
                echo -e "${RED}CRITICAL${NC}: Token age: ${token_age} minutes, Status: ${token_status}"
                log "CRITICAL: Token expires soon - Age: ${token_age} minutes"
                ;;
            "WARNING"*)
                echo -e "${YELLOW}WARNING${NC}: Token age: ${token_age} minutes, Status: ${token_status}"
                log "WARNING: Token aging - Age: ${token_age} minutes"
                ;;
            "HEALTHY")
                echo -e "${GREEN}HEALTHY${NC}: Token age: ${token_age} minutes, Status: ${token_status}"
                log "INFO: Token healthy - Age: ${token_age} minutes"
                ;;
            *)
                echo -e "${RED}UNKNOWN${NC}: Token status unknown"
                log "ERROR: Token status unknown"
                ;;
        esac
        
        echo "  Authenticated: $authenticated"
        echo "  Created at: $created_at"
        echo "  Age: $token_age minutes"
        
        # If token is older than 19 minutes, force re-authentication
        if [ "$token_age" -ge "19" ] && [ "$authenticated" = "true" ]; then
            echo -e "${RED}EMERGENCY${NC}: Token is $token_age minutes old, forcing re-authentication"
            log "EMERGENCY: Forcing re-authentication - token age: $token_age minutes"
            force_reauth
        fi
        
    else
        echo -e "${RED}ERROR${NC}: Failed to get vault status (HTTP: $http_code)"
        log "ERROR: Failed to get vault status - HTTP code: $http_code"
    fi
}

force_reauth() {
    echo "Forcing re-authentication..."
    local response=$(curl -s -w "%{http_code}" -X POST "$CONFIG_SERVER_URL/management/vault/reauth" -o /tmp/reauth_response.json)
    local http_code=${response: -3}
    
    if [ "$http_code" = "200" ]; then
        local status=$(jq -r '.status' /tmp/reauth_response.json 2>/dev/null || echo "unknown")
        local message=$(jq -r '.message' /tmp/reauth_response.json 2>/dev/null || echo "unknown")
        
        if [ "$status" = "success" ]; then
            echo -e "${GREEN}SUCCESS${NC}: Re-authentication successful"
            log "SUCCESS: Manual re-authentication successful"
        else
            echo -e "${RED}FAILED${NC}: Re-authentication failed - $message"
            log "ERROR: Manual re-authentication failed - $message"
        fi
    else
        echo -e "${RED}ERROR${NC}: Failed to trigger re-authentication (HTTP: $http_code)"
        log "ERROR: Failed to trigger re-authentication - HTTP code: $http_code"
    fi
}

check_health() {
    local response=$(curl -s -w "%{http_code}" "$CONFIG_SERVER_URL/management/vault/health" -o /tmp/vault_health.json)
    local http_code=${response: -3}
    
    if [ "$http_code" = "200" ]; then
        local connectivity=$(jq -r '.vault_connectivity' /tmp/vault_health.json 2>/dev/null || echo "unknown")
        echo "Vault Connectivity: $connectivity"
    else
        echo -e "${RED}ERROR${NC}: Health check failed (HTTP: $http_code)"
        log "ERROR: Health check failed - HTTP code: $http_code"
    fi
}

test_config_retrieval() {
    echo "Testing configuration retrieval..."
    local response=$(curl -s -w "%{http_code}" "$CONFIG_SERVER_URL/myapp/default" -o /tmp/config_test.json)
    local http_code=${response: -3}
    
    case "$http_code" in
        "200")
            echo -e "${GREEN}SUCCESS${NC}: Configuration retrieval successful"
            ;;
        "403")
            echo -e "${RED}FORBIDDEN${NC}: 403 error - token likely expired!"
            log "ERROR: 403 Forbidden - batch token expired"
            force_reauth
            ;;
        *)
            echo -e "${YELLOW}WARNING${NC}: Configuration retrieval returned HTTP $http_code"
            log "WARNING: Configuration retrieval returned HTTP $http_code"
            ;;
    esac
}

# Main monitoring loop
monitor() {
    echo "Starting Batch Token Monitor for Spring Cloud Config Server"
    echo "Config Server URL: $CONFIG_SERVER_URL"
    echo "Check Interval: $CHECK_INTERVAL seconds"
    echo "Log File: $LOG_FILE"
    echo "Press Ctrl+C to stop"
    echo "=========================="
    
    log "Batch Token Monitor started"
    
    while true; do
        echo -e "\n$(date '+%Y-%m-%d %H:%M:%S') - Checking vault status..."
        check_vault_status
        
        echo -e "\nChecking health..."
        check_health
        
        echo -e "\nTesting configuration retrieval..."
        test_config_retrieval
        
        echo "=========================="
        sleep "$CHECK_INTERVAL"
    done
}

# Command line options
case "${1:-monitor}" in
    "monitor")
        monitor
        ;;
    "status")
        check_vault_status
        ;;
    "health")
        check_health
        ;;
    "reauth")
        force_reauth
        ;;
    "test")
        test_config_retrieval
        ;;
    *)
        echo "Usage: $0 [monitor|status|health|reauth|test]"
        echo "  monitor - Start continuous monitoring (default)"
        echo "  status  - Check vault status once"
        echo "  health  - Check vault health once"
        echo "  reauth  - Force re-authentication"
        echo "  test    - Test configuration retrieval"
        exit 1
        ;;
esac