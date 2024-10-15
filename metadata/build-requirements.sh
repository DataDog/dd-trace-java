#!/bin/bash
#
# This script builds the requirements.json file based on
# - the base-requirements.json as base file,
# - the denied-arguments.tsv as rules to exclude application from their arguments (main classes, System properties, application arguments),
# - the denied-environment-variables.tsv as rules to exclude applications from their exported environment variables.
#

log-json() {
    local JSON=$1
    echo "Logging JSON"
    echo "$JSON" | jq
}

#
# Initialize requirements from base file
#
JSON=$(cat base-requirements.json)

#
# Append deny list entries based on arguments
#
while read -r ENTRY; do
    # Skip comments or empty lines
    if [[ -z $ENTRY || $ENTRY == \#* ]]; then
        continue
    fi
    # Take first word
    IDENTIFIER=$(echo "$ENTRY" | awk '{print $1}')
    # Take second word
    ARGUMENT=$(echo "$ENTRY" | awk '{print $2}')
    # Take the rest as description
    DESCRIPTION=$(echo "$ENTRY" | awk '{for(i=3;i<=NF;++i) printf "%s%s", $i, (i<NF)?" ":""}')
    # Build deny list entry
    DENY_ENTRY=$(cat <<-END
    {
        "id": "$IDENTIFIER",
        "description": "$DESCRIPTION",
        "os": null,
        "cmds": ["**/java"],
        "args": [{
          "args": ["$ARGUMENT"],
          "position": null
        }],
        "envars": null
    }
END
    )
    JSON=$(echo "$JSON" | jq ".deny += [$DENY_ENTRY]")
done < denied-arguments.tsv

#
# Append deny list entries based on environment variables
#
while read -r ENTRY; do
    # Skip comments or empty lines
    if [[ -z $ENTRY || $ENTRY == \#* ]]; then
        continue
    fi
    # Take first word
    IDENTIFIER=$(echo "$ENTRY" | awk '{print $1}')
    # Take second word
    ENVIRONMENT_VARIABLE=$(echo "$ENTRY" | awk '{print $2}')
    # Take the rest as description
    DESCRIPTION=$(echo "$ENTRY" | awk '{for(i=3;i<=NF;++i) printf "%s%s", $i, (i<NF)?" ":""}')
    # Build deny list entry
    DENY_ENTRY=$(cat <<-END
    {
        "id": "$IDENTIFIER",
        "description": "$DESCRIPTION",
        "os": null,
        "cmds": ["**/java"],
        "args": [],
        "envars": {
            "$ENVIRONMENT_VARIABLE": null
        }
    }
END
    )
    JSON=$(echo "$JSON" | jq ".deny += [$DENY_ENTRY]")
done < denied-environment-variables.tsv

log-json "$JSON"
echo "$JSON" > requirements.json
