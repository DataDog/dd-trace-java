#!/bin/bash

# Absolute path to this script, e.g. /home/user/bin/foo.sh
SCRIPT_PATH=$(readlink -f "$0")
# Absolute path this script is in, thus /home/user/bin
export BASE_DIR=$(dirname "${SCRIPT_PATH}")

source ${BASE_DIR}/src/test/shell/functions.sh

for func in "${@}"
do
    echo "*** Running ${func} ***"
    $func
done
