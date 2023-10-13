#!/usr/bin/env bash
set -eu

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

if [[ -n $(git diff --stat) ]]; then
    echo "Current git checkout is dirty, commit or discard changes first."
    exit 1
fi

current_commit="$(grep ^default_system_tests_commit: config.continue.yml.j2 | sed -e 's~^.* ~~g')"
latest_commit="$(git ls-remote git@github.com:DataDog/system-tests.git refs/heads/main | cut -f 1)"

echo "Current commit: $current_commit"
echo "Latest commit: $latest_commit"
if [[ "$current_commit" = "$latest_commit" ]]; then
    echo "No change"
    exit 0
fi

echo "Updating config.yml"
sed -i -e "s~${current_commit?}~${latest_commit?}~g" config.continue.yml.j2
git diff config.continue.yml.j2 | cat
git commit -m "Update system-tests to $latest_commit" config.continue.yml.j2
