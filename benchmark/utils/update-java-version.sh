readonly target=$1
readonly NEW_PATH=$(echo "${PATH}" | sed -e "s@/usr/lib/jvm/[[:digit:]]\+@/usr/lib/jvm/${target}@g")
export PATH="${NEW_PATH}"

java --version
