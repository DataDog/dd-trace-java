#!/bin/bash

#
# Check required JVM.
#

function get-java-major-version() {
    local JAVA_COMMAND=$1
    local VERSION_STRING
    VERSION_STRING=$("$JAVA_COMMAND" -version 2>&1 | grep version | head -n 1)

    # Extract version number from output like 'version "21.0.1"' or 'version "1.8.0_392"'
    if echo "$VERSION_STRING" | grep -q 'version "1\.'; then
        # Old versioning scheme (Java 8 and earlier): 1.X.Y_Z -> major version is X
        echo "$VERSION_STRING" | sed -n 's/.*version "1\.\([0-9]*\).*/\1/p'
    else
        # New versioning scheme (Java 9+): X.Y.Z -> major version is X
        echo "$VERSION_STRING" | sed -n 's/.*version "\([0-9]*\).*/\1/p'
    fi
}

function check-jdk() {
    local JAVA_COMMAND=$1
    local MIN_JAVA_VERSION=$2
    local JAVA_VERSION
    JAVA_VERSION=$(get-java-major-version "$JAVA_COMMAND")

    if [ -z "$JAVA_VERSION" ]; then
        echo "❌ Could not determine Java version from $JAVA_COMMAND." >&2
        exit 1
    elif [ "$JAVA_VERSION" -lt "$MIN_JAVA_VERSION" ]; then
        echo "🟨 $JAVA_COMMAND refers to JDK $JAVA_VERSION but JDK $MIN_JAVA_VERSION or above is recommended." >&2
    else
        echo "✅ $JAVA_COMMAND is set to JDK $JAVA_VERSION."
    fi
}

function show-available-jdks() {
    ./gradlew -q javaToolchains | awk '
        /^ \+ / {
            # Extract JDK name from lines starting with " + "
            jdk_name = substr($0, 4)
        }
        /^     \| Location:/ {
            # Extract location and print with JDK name
            sub(/^     \| Location: +/, "")
            if (jdk_name != "") {
                print "✅ " jdk_name " from " $0 "."
                jdk_name = ""
            }
        }
    '
}

echo "ℹ️ Checking JDK:"
if [ -e "$JAVA_HOME" ]; then
    check-jdk "$JAVA_HOME/bin/java" "21"
elif command -v java &> /dev/null; then
    check-jdk "java" "21"
else
    echo "❌ No Java installation found. Please install JDK 21 or above." >&2
    exit 1
fi
echo "ℹ️ Checking other JDKs available for testing:"
show-available-jdks


#
# Check git configuration.
#

function check-command() {
    local COMMAND_NAME=$1
    if command -v "$COMMAND_NAME" &> /dev/null; then
        echo "✅ The $COMMAND_NAME command line is installed."
    else
        echo "❌ The $COMMAND_NAME command line is missing. Please install $COMMAND_NAME." >&2
        exit 1
    fi
}

function get-file-hash() {
    local FILE=$1
    md5sum "$FILE" | awk '{print $1}'
}

function look-for-hook() {
    local HOOK_NAME=$1
    local HOOK_CHECKSUM
    HOOK_CHECKSUM=$(get-file-hash .githooks/$HOOK_NAME)
    local HOOKS_PATH
    HOOKS_PATH=$(git config core.hooksPath)

    if [ -e ".git/hooks/$HOOK_NAME" ] && [ "$(get-file-hash .git/hooks/$HOOK_NAME)" == "$HOOK_CHECKSUM" ]; then
        echo "✅ $HOOK_NAME hook is installed in repository."
    elif [ -e "$HOOKS_PATH/$HOOK_NAME" ] && [ "$(get-file-hash $HOOKS_PATH/$HOOK_NAME)" == "$HOOK_CHECKSUM" ]; then
        echo "✅ $HOOK_NAME hook is installed in git hooks path."
    else
        echo "🟨 $HOOK_NAME hook was not found (optional but recommended)."
    fi
}

function check-git-config() {
    local CONFIG_NAME=$1
    local EXPECTED_VALUE=$2
    local ACTUAL_VALUE
    ACTUAL_VALUE=$(git config "$CONFIG_NAME")
    if [ "$ACTUAL_VALUE" == "$EXPECTED_VALUE" ]; then
        echo "✅ git config $CONFIG_NAME is set to $EXPECTED_VALUE."
    elif [ -z "$ACTUAL_VALUE" ]; then
        echo "❌ git config $CONFIG_NAME is not set. Please run 'git config set $CONFIG_NAME $EXPECTED_VALUE'."
    else
        echo "🟨 git config $CONFIG_NAME is set to $ACTUAL_VALUE (expected: $EXPECTED_VALUE). Please run 'git config set $CONFIG_NAME $EXPECTED_VALUE'."
    fi
}

function check-submodule-initialization() {
    if [ -e ".gitmodules" ]; then
        if git submodule status | grep '^-' > /dev/null; then
            echo "❌ A git submodule are not initialized. Please run 'git submodule update --init --recursive'."
        else
            echo "✅ All git submodules are initialized."
        fi
    fi
}

echo "ℹ️ Checking git configuration:"
check-command "git"
look-for-hook "pre-commit"
check-git-config "submodule.recurse" "true"
check-submodule-initialization


#
# Check Docker environment.
#

function check-docker-server() {
    if docker info &> /dev/null; then
        echo "✅ The Docker server is running."
    else
        echo "🟨 The Docker server is not running. Please start it be able to run all tests."
    fi
}

echo "ℹ️ Checking Docker environment:"
check-command "docker"
check-docker-server


#
# Check shell environment.
# (unused for now)
#

function check-ulimit() {
    local LIMIT_NAME="File descriptor limit"
    local EXPECTED_LIMIT=$1
    local ACTUAL_LIMIT
    ACTUAL_LIMIT=$(ulimit -n)
    if [ "$ACTUAL_LIMIT" -ge "$EXPECTED_LIMIT" ]; then
        echo "✅ $LIMIT_NAME is set to $ACTUAL_LIMIT."
    else
        echo "🟨 $LIMIT_NAME is set to $ACTUAL_LIMIT, which could be an issue for gradle build. Please set it locally to $EXPECTED_LIMIT or greater using ulimit."
    fi
}
