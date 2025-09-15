#!/usr/bin/env bash

# Save all important profiles into (project-root)/profiles
# This folder will be saved by gitlab and available after test runs.

set -e
#Enable '**' support
shopt -s globstar

PROFILES_DIR=./profiles
mkdir -p $PROFILES_DIR >/dev/null 2>&1

cp /tmp/*.jfr $PROFILES_DIR || true

function save_profiles () {
    project_to_save=$1
    echo "saving profiles for $project_to_save"

    profile_path=$PROFILES_DIR/$project_to_save
    mkdir -p $profile_path
    cp workspace/$project_to_save/build/*.jfr $profile_path/ || true
}

shopt -s globstar

for profile_path in workspace/**/build/profiles; do
    profile_path=${profile_path//workspace\//}
    profile_path=${profile_path//\/build\/profiles/}
    save_profiles $profiles
done

tar -cvzf profiles.tar $PROFILES_DIR
