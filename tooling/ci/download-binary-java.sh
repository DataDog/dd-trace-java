#!/bin/bash

# Unless explicitly stated otherwise all files in this repository are licensed under the the Apache License Version 2.0.
# This product includes software developed at Datadog (https://www.datadoghq.com/).
# Copyright 2021 Datadog, Inc.


set -eu

assert_version_is_dev() {

  if [ $VERSION = 'dev' ]; then
    return 0
  fi

  echo "Don't know how to load version $VERSION for java"

  exit 1
}

get_circleci_artifact() {

    SLUG=$1
    WORKFLOW_NAME=$2
    JOB_NAME=$3
    ARTIFACT_PATTERN=$4
    ARTIFACT_NAME=$5

    echo "CircleCI: https://app.circleci.com/pipelines/$SLUG?branch=master"
    PIPELINES=$(curl --silent https://circleci.com/api/v2/project/$SLUG/pipeline?branch=master -H "Circle-Token: $CIRCLECI_TOKEN")

    for i in {0..30}; do
        PIPELINE_ID=$(echo $PIPELINES| jq -r ".items[$i].id")
        PIPELINE_NUMBER=$(echo $PIPELINES | jq -r ".items[$i].number")

        echo "Trying pipeline #$i $PIPELINE_NUMBER/$PIPELINE_ID"
        WORKFLOWS=$(curl --silent https://circleci.com/api/v2/pipeline/$PIPELINE_ID/workflow -H "Circle-Token: $CIRCLECI_TOKEN")

        QUERY=".items[] | select(.name == \"$WORKFLOW_NAME\") | .id"
        WORKFLOW_IDS=$(echo $WORKFLOWS | jq -r "$QUERY")

        if [ ! -z "$WORKFLOW_IDS" ]; then

            for WORKFLOW_ID in $WORKFLOW_IDS; do
                echo "=> https://app.circleci.com/pipelines/$SLUG/$PIPELINE_NUMBER/workflows/$WORKFLOW_ID"

                JOBS=$(curl --silent https://circleci.com/api/v2/workflow/$WORKFLOW_ID/job -H "Circle-Token: $CIRCLECI_TOKEN")

                QUERY=".items[] | select(.name == \"$JOB_NAME\" and .status==\"success\")"
                JOB=$(echo $JOBS | jq "$QUERY")

                if [ ! -z "$JOB" ]; then
                    break
                fi
            done

            if [ ! -z "$JOB" ]; then
                break
            fi
        fi
    done

    if [ -z "$JOB" ]; then
        echo "Oooops, I did not found any successful pipeline"
        exit 1
    fi

    JOB_NUMBER=$(echo $JOB | jq -r ".job_number")
    JOB_ID=$(echo $JOB | jq -r ".id")

    echo "Job number/ID: $JOB_NUMBER/$JOB_ID"
    echo "Job URL: https://app.circleci.com/pipelines/$SLUG/$PIPELINE_NUMBER/workflows/$WORKFLOW_ID/jobs/$JOB_NUMBER"

    ARTIFACTS=$(curl --silent https://circleci.com/api/v2/project/$SLUG/$JOB_NUMBER/artifacts -H "Circle-Token: $CIRCLECI_TOKEN")
    QUERY=".items[] | select(.path | test(\"$ARTIFACT_PATTERN\"))"
    ARTIFACT_URL=$(echo $ARTIFACTS | jq -r "$QUERY | .url")

    if [ -z "$ARTIFACT_URL" ]; then
        echo "Oooops, I did not found any artifact that satisfy this pattern: $ARTIFACT_PATTERN. Here is the list:"
        echo $ARTIFACTS | jq -r ".items[] | .path"
        exit 1
    fi

    echo "Artifact URL: $ARTIFACT_URL"
    echo "Artifact name: $ARTIFACT_NAME"
    echo "Downloading artifact..."

    curl --silent -L $ARTIFACT_URL --output $ARTIFACT_NAME
}

get_github_action_artifact() {
    rm -rf artifacts artifacts.zip

    SLUG=$1
    WORKFLOW=$2
    BRANCH=$3
    PATTERN=$4

    # query filter seems not to be working ??
    WORKFLOWS=$(curl --silent --fail --show-error -H "Authorization: token $GH_TOKEN" "https://api.github.com/repos/$SLUG/actions/workflows/$WORKFLOW/runs?per_page=100")

    QUERY="[.workflow_runs[] | select(.conclusion != \"failure\" and .head_branch == \"$BRANCH\" and .status == \"completed\")][0]"
    ARTIFACT_URL=$(echo $WORKFLOWS | jq -r "$QUERY | .artifacts_url")
    HTML_URL=$(echo $WORKFLOWS | jq -r "$QUERY | .html_url")
    echo "Load artifact $HTML_URL" 
    ARTIFACTS=$(curl --silent -H "Authorization: token $GH_TOKEN" $ARTIFACT_URL)

    ARCHIVE_URL=$(echo $ARTIFACTS | jq -r '.artifacts[0].archive_download_url')
    echo "Load archive $ARCHIVE_URL"

    curl -H "Authorization: token $GH_TOKEN" --output artifacts.zip -L $ARCHIVE_URL 

    mkdir -p artifacts/
    unzip artifacts.zip -d artifacts/

    find artifacts/ -type f -name $PATTERN -exec cp '{}' . ';'

    rm -rf artifacts artifacts.zip
}

get_github_release_asset() {
    SLUG=$1
    PATTERN=$2

    release=$(curl --silent --fail --show-error -H "Authorization: token $GH_TOKEN" "https://api.github.com/repos/$SLUG/releases/latest")

    name=$(echo $release | jq -r ".assets[].name | select(test(\"$PATTERN\"))")
    url=$(echo $release | jq -r ".assets[].browser_download_url | select(test(\"$PATTERN\"))")

    echo "Load $url"

    curl -H "Authorization: token $GH_TOKEN" --output $name -L $url 
}

if test -f ".env"; then
    source .env
fi


VERSION=${1:-'dev'}

echo "Load $VERSION binary for java"

mkdir -p tooling/ci/binaries
cd tooling/ci/binaries

if [ $VERSION = 'dev' ]; then
    get_circleci_artifact "gh/DataDog/dd-trace-java" "nightly" "build" "libs/dd-java-agent-.*(-SNAPSHOT)?.jar" "dd-java-agent.jar"
else
    get_github_release_asset "DataDog/dd-trace-java" "dd-java-agent.jar"
fi
    
