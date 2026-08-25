#!/bin/bash
# ==============================================================================
# Script to upload CI/CD variables from local .env to GitLab via REST API
# ==============================================================================
set -e

ENV_FILE=".env"
if [ -n "$PROJECT_ROOT" ] && [ -f "$PROJECT_ROOT/.env" ]; then
    ENV_FILE="$PROJECT_ROOT/.env"
fi

if [ ! -f "$ENV_FILE" ]; then
    echo "ERROR: .env file not found ($ENV_FILE). Please create a .env file before running this script."
    exit 1
fi

# Load configs from .env if they are not already set in environment
if [ -z "$GITLAB_TOKEN" ]; then
    GITLAB_TOKEN=$(grep -E "^GITLAB_TOKEN=" "$ENV_FILE" | cut -d'=' -f2- | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//")
fi

if [ -z "$GITLAB_URL" ]; then
    GITLAB_URL=$(grep -E "^GITLAB_URL=" "$ENV_FILE" | cut -d'=' -f2- | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//")
fi

if [ -z "$PROJECT_PATH" ]; then
    PROJECT_PATH=$(grep -E "^PROJECT_PATH=" "$ENV_FILE" | cut -d'=' -f2- | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//")
fi

# Apply script-level defaults if they are still empty
GITLAB_URL="${GITLAB_URL:-https://git.mam.dev}"
PROJECT_PATH="${PROJECT_PATH:-bnerushev%2Fmail-and-media-shop-v2}"

if [ -z "$GITLAB_TOKEN" ]; then
    echo "ERROR: GITLAB_TOKEN is not set in environment and not found in $ENV_FILE."
    echo "Please add GITLAB_TOKEN=your_token to your .env file or export it."
    exit 1
fi

echo "Loading variables from: $ENV_FILE"
echo "Uploading variables to GitLab project: $PROJECT_PATH ($GITLAB_URL)"

while IFS= read -r line || [ -n "$line" ]; do
    # Strip leading/trailing whitespace
    line=$(echo "$line" | xargs)

    # Skip comments and empty lines
    [[ "$line" =~ ^#.*$ ]] && continue
    [[ -z "$line" ]] && continue
    [[ "$line" != *"="* ]] && continue

    KEY=$(echo "$line" | cut -d'=' -f1 | xargs)
    VALUE=$(echo "$line" | cut -d'=' -f2- | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//")

    # Skip if key or value is empty
    if [ -z "$KEY" ] || [ -z "$VALUE" ]; then
        continue
    fi

    echo -n "Uploading $KEY... "

    RESPONSE_CODE=$(curl --silent --output /dev/null --write-out "%{http_code}" \
        --request POST \
        --header "PRIVATE-TOKEN: $GITLAB_TOKEN" \
        --url "$GITLAB_URL/api/v4/projects/$PROJECT_PATH/variables" \
        --form "key=$KEY" \
        --form "value=$VALUE" \
        --form "protected=false" \
        --form "masked=false")

    if [ "$RESPONSE_CODE" -eq 201 ]; then
        echo "✅ Created"
    elif [ "$RESPONSE_CODE" -eq 400 ]; then
        UPDATE_CODE=$(curl --silent --output /dev/null --write-out "%{http_code}" \
            --request PUT \
            --header "PRIVATE-TOKEN: $GITLAB_TOKEN" \
            --url "$GITLAB_URL/api/v4/projects/$PROJECT_PATH/variables/$KEY" \
            --form "value=$VALUE")
        if [ "$UPDATE_CODE" -eq 200 ]; then
            echo "🔄 Updated"
        else
            echo "❌ Failed (HTTP $UPDATE_CODE)"
        fi
    else
        echo "❌ Failed (HTTP $RESPONSE_CODE)"
    fi
done < "$ENV_FILE"

echo "All variables processed successfully!"

