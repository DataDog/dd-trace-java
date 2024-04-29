#!/bin/bash

#
# Check required JVM.
#

function check-jvm() {
    local JAVA_HOME_NAME=$1
    local EXPECTED_JAVA_VERSION=$2
    if [ -z ${!JAVA_HOME_NAME} ]; then
        echo "❌ $JAVA_HOME_NAME is not set. Please set $JAVA_HOME_NAME to refer to a JDK $EXPECTED_JAVA_VERSION installation." >&2
        exit 1
    elif ! ${!JAVA_HOME_NAME}/bin/java -version 2>&1 | grep -q "version \"$EXPECTED_JAVA_VERSION\." ; then
        echo "❌ $JAVA_HOME_NAME is set to ${!JAVA_HOME_NAME}, but it does not refer to a JDK $EXPECTED_JAVA_VERSION installation." >&2
        exit 1
    else
        echo "✅ $JAVA_HOME_NAME is set to $(readlink -f ${!JAVA_HOME_NAME})."
    fi
}

echo "ℹ️  Checking required JVM:"
if [ -e "$JAVA_HOME" ]; then
    check-jvm "JAVA_HOME" "1.8"
fi
check-jvm "JAVA_8_HOME" "1.8"
check-jvm "JAVA_11_HOME" "11"
check-jvm "JAVA_17_HOME" "17"
check-jvm "JAVA_21_HOME" "21"
check-jvm "JAVA_GRAALVM17_HOME" "17"


#
# Check git configuration.
#

function check-command() {
    local COMMAND_NAME=$1
    if command -v $COMMAND_NAME &> /dev/null; then
        echo "✅ The $COMMAND_NAME command line is installed."
    else
        echo "❌ The $COMMAND_NAME command line is missing. Please install $COMMAND_NAME." >&2
        exit 1
    fi
}

function get-file-hash() {
    local FILE=$1
    echo $(md5sum $FILE | awk '{print $1}')
}

function look-for-hook() {
    local HOOK_NAME=$1
    local HOOK_CHECKSUM=$(get-file-hash .githooks/$HOOK_NAME)
    local HOOKS_PATH=$(git config core.hooksPath)
    local HOOK_FOUND=false

    if [ -e ".git/hooks/$HOOK_NAME" ] && [ "$(get-file-hash .git/hooks/$HOOK_NAME)" == "$HOOK_CHECKSUM" ]; then
        echo "✅ $HOOK_NAME hook is installed in repository."
    elif [ -e "$HOOKS_PATH/$HOOK_NAME" ] && [ "$(get-file-hash $HOOKS_PATH/$HOOK_NAME)" == "$HOOK_CHECKSUM" ]; then
        echo "✅ $HOOK_NAME hook is installed in git hooks path."
    else
        echo "🟨 $HOOK_NAME hook was not found (optional but recommanded)."
    fi
}

function check-git-config() {
    local CONFIG_NAME=$1
    local EXPECTED_VALUE=$2
    local ACTUAL_VALUE=$(git config $CONFIG_NAME)
    if [ "$ACTUAL_VALUE" == "$EXPECTED_VALUE" ]; then
        echo "✅ git config $CONFIG_NAME is set to $EXPECTED_VALUE."
    elif [ -z "$ACTUAL_VALUE" ]; then
        echo "❌ git config $CONFIG_NAME is not set. Please locally set $CONFIG_NAME to $EXPECTED_VALUE."
    else
        echo "🟨 git config $CONFIG_NAME is set to $ACTUAL_VALUE (expected: $EXPECTED_VALUE)."
    fi
}

echo "ℹ️  Checking git configuration:"
check-command "git"
look-for-hook "pre-commit"
check-git-config "submodule.recurse" "true"


#
# Check shell configuration.
#

function check-ulimit() {
    local LIMIT_NAME="File descriptor limit"
    local EXPECTED_LIMIT=$1
    local ACTUAL_LIMIT=$(ulimit -n)
    if [ "$ACTUAL_LIMIT" -ge "$EXPECTED_LIMIT" ]; then
        echo "✅ $LIMIT_NAME is set to $ACTUAL_LIMIT."
    else
        echo "🟨 $LIMIT_NAME is set to $ACTUAL_LIMIT, which could be an issue for gradle build. Please set it locally to $EXPECTED_LIMIT or greater using ulimit."
    fi
}

function check-docker-server() {
    if docker info &> /dev/null; then
        echo "✅ The Docker server is running."
    else
        echo "🟨 The Docker server is not running. Please start it be able to run all tests."
    fi
}

echo "ℹ️  Checking shell configuration:"
check-ulimit 1024
check-command "docker"
check-docker-server
