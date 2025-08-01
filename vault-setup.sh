#!/bin/bash

# Vault Setup Script for Spring Cloud Config Server
# This script configures Vault with AppRole authentication and service tokens

# Set Vault address
export VAULT_ADDR=${VAULT_ADDR:-http://localhost:8200}

# Check if Vault is running
if ! curl -s "$VAULT_ADDR/v1/sys/health" > /dev/null; then
    echo "Error: Vault is not running at $VAULT_ADDR"
    exit 1
fi

echo "Setting up Vault for Spring Cloud Config Server..."

# Enable AppRole auth method if not already enabled
vault auth enable -path=approle approle 2>/dev/null || echo "AppRole auth method already enabled"

# Create policy for config server
vault policy write config-server-policy - <<EOF
# Allow reading secrets
path "secret/data/*" {
  capabilities = ["read"]
}

# Allow token operations for renewal
path "auth/token/create" {
  capabilities = ["create", "update"]
}

path "auth/token/renew" {
  capabilities = ["update"]
}

path "auth/token/renew-self" {
  capabilities = ["update"]
}

# Allow reading token information
path "auth/token/lookup-self" {
  capabilities = ["read"]
}
EOF

echo "Created config-server-policy"

# Create AppRole with service token type (not batch tokens)
vault write auth/approle/role/config-server \
    token_policies="config-server-policy" \
    token_ttl=1h \
    token_max_ttl=24h \
    token_type=service \
    bind_secret_id=true \
    token_num_uses=0

echo "Created config-server AppRole"

# Get Role ID
ROLE_ID=$(vault read -field=role_id auth/approle/role/config-server/role-id)
echo "Role ID: $ROLE_ID"

# Generate Secret ID
SECRET_ID=$(vault write -field=secret_id auth/approle/role/config-server/secret-id)
echo "Secret ID: $SECRET_ID"

# Test authentication
echo "Testing AppRole authentication..."
TOKEN=$(vault write -field=token auth/approle/login role_id="$ROLE_ID" secret_id="$SECRET_ID")

if [ -n "$TOKEN" ]; then
    echo "✅ AppRole authentication successful"
    
    # Check token type
    TOKEN_TYPE=$(VAULT_TOKEN="$TOKEN" vault token lookup -field=type)
    echo "Token type: $TOKEN_TYPE"
    
    if [ "$TOKEN_TYPE" = "service" ]; then
        echo "✅ Service token created successfully (renewable)"
    else
        echo "⚠️  Warning: Token type is not 'service', got '$TOKEN_TYPE'"
    fi
    
    # Test token renewal
    echo "Testing token renewal..."
    RENEWED_TOKEN=$(VAULT_TOKEN="$TOKEN" vault token renew -field=auth.client_token)
    if [ -n "$RENEWED_TOKEN" ]; then
        echo "✅ Token renewal successful"
    else
        echo "❌ Token renewal failed"
    fi
else
    echo "❌ AppRole authentication failed"
    exit 1
fi

# Create some sample secrets for testing
vault kv put secret/myapp/default \
    spring.datasource.url="jdbc:postgresql://localhost:5432/myapp" \
    spring.datasource.username="myuser" \
    spring.datasource.password="mypassword"

vault kv put secret/myapp/prod \
    spring.datasource.url="jdbc:postgresql://prod-db:5432/myapp" \
    spring.datasource.username="produser" \
    spring.datasource.password="prodpassword"

echo "✅ Sample secrets created"

echo ""
echo "==================================="
echo "Vault setup completed successfully!"
echo "==================================="
echo ""
echo "Use these values in your application:"
echo "VAULT_ROLE_ID=$ROLE_ID"
echo "VAULT_SECRET_ID=$SECRET_ID"
echo ""
echo "Add these to your .env file or environment variables"