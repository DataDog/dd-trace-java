#!/usr/bin/env bash
set -eu

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_DIR="$SCRIPT_DIR/.."
readonly TMP_REPO_DIR="$REPO_DIR/tmp-release-repo"

function prepare_tmp_repo() {
    rm -rf "${TMP_REPO_DIR?}"
    git clone --local "${REPO_DIR}/.git" "${TMP_REPO_DIR}"
    cd "${TMP_REPO_DIR}"
    git remote add origin git@github.com:DataDog/dd-trace-java.git
    git fetch origin
}

function previous_tag() {
    git describe --abbrev=0
}

function merge_commits_between() {
    local prev="${1}" cur="${2}"
    git log --format=%H --first-parent "${prev}..${cur}"
}

function check_merge_commits() {
    echo "Checking merge commits..."
    local cur_ref=HEAD prev_tag commit subject err
    prev_tag="$(previous_tag)"
    for commit in $(merge_commits_between "${prev_tag}" "${cur_ref}"); do
        subject="$(git show --format=%s --no-patch "${commit}")"
        if [[ ${subject} =~ Merge\ pull\ request\ #[0-9]+\ .* ]]; then
            continue
        elif [[ ${subject} =~ .*\ \(#[0-9]+\) ]]; then
            continue
        fi
        echo -e "Suspicious commit: ${commit} ${subject}"
        err=true
    done
    if [[ ${err:-} = true ]]; then
        if [[ ${SKIP_MERGE_COMMITS_CHECK:-} = true ]]; then
            echo "Some suspicious commits were found, but --skip-merge-commit-check was set, continuing..."
        else
            echo "Please, check the suspicious commits above. If everything is fine, run again with --skip-merge-commit-check"
            return 1
        fi
    fi
}

function gradle_release_dry_run() {
    local output exit_status next_tag input
    echo "Running ./gradlew release -Prelease.dryRun"
    set +e
    output="$(./gradlew release -Prelease.dryRun)"
    exit_status="$?"
    set -e
    if [[ $exit_status != 0 ]]; then
        echo "Running gradle failed:"
        echo -e "${output}"
        return 1
    fi
    next_tag="$(echo "${output}" | grep 'Creating tag: ' | sed -e 's~.* ~~g')"
    echo "Release will be tagged as ${next_tag}"
    if [[ "${next_tag}" = "${NEXT_TAG}" ]]; then
        return 0
    elif [[ -n "${NEXT_TAG}" ]]; then
        echo "You've set tag ${NEXT_TAG}, but we're about to tag ${next_tag}"
        return 1
    fi
    while true; do
        echo "Release will be tagged as ${next_tag}"
        echo "If this is correct, enter [y/Y]"
        read -r input
        if [[ "${input}" =~ [yY] ]]; then
            return 0
        fi
    done
}

SKIP_MERGE_COMMITS_CHECK=
NEXT_TAG=
while [[ $# -gt 0 ]]; do
    case "$1" in
    --skip-merge-commit-check) SKIP_MERGE_COMMITS_CHECK=true ;;
    --tag) NEXT_TAG="${2}" ; shift ;;
    *)
        echo "Invalid argument: ${1}"
        exit 1
        ;;
    esac
    shift
done

prepare_tmp_repo
check_merge_commits
gradle_release_dry_run