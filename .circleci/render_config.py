#!/usr/bin/env python3

import os
import os.path
import subprocess
import time

import jinja2
import requests

SCRIPT_DIR = os.path.dirname(__file__)

TPL_FILENAME = "config.continue.yml.j2"
OUT_FILENAME = "config.continue.yml"
GENERATED_CONFIG_PATH = os.path.join(SCRIPT_DIR, OUT_FILENAME)

# JDKs that will run on every pipeline.
ALWAYS_ON_JDKS = {"8", "17", "21"}
# And these will run only in master and release/ branches.
MASTER_ONLY_JDKS = {
    "11",
    "ibm8",
    "oracle8",
    "semeru8",
    "zulu8",
    "semeru11",
    "zulu11",
    "semeru17",
    "ubuntu17",
}
# Version to use for all the base Docker images, see
# https://github.com/DataDog/dd-trace-java-docker-build/pkgs/container/dd-trace-java-docker-build
DOCKER_IMAGE_VERSION="v25.01"

# Get labels from pull requests to override some defaults for jobs to run.
# `run-tests: all` will run all tests.
# `run-tests: ibm8` will run the IBM 8 tests.
# `run-tests: flaky` for flaky tests jobs.
pr_url = os.environ.get("CIRCLE_PULL_REQUEST")
if pr_url:
    pr_num = int(pr_url.split("/")[-1])
    headers = {}
    gh_token = os.environ.get("GH_TOKEN")
    if gh_token:
        headers["Authorization"] = gh_token
    else:
        print("Missing GH_TOKEN, trying anonymously")
    for _ in range(20):
        try:
            resp = requests.get(
                f"https://api.github.com/repos/DataDog/dd-trace-java/pulls/{pr_num}",
                timeout=1,
                headers=headers,
            )
            resp.raise_for_status()
        except Exception as e:
            print(f"Request failed: {e}")
            time.sleep(1)
            continue
        data = resp.json()
        break

    labels = data.get("labels", [])
    labels = [l["name"] for l in labels]
    labels = {
        l.replace("run-tests: ", "") for l in labels if l.startswith("run-tests: ")
    }
    # get the base reference (e.g. `master`), commit hash is also available at the `sha` field.
    pr_base_ref = data.get("base", {}).get("ref")
else:
    labels = set()
    pr_base_ref = ""


branch = os.environ.get("CIRCLE_BRANCH", "")
run_all = "all" in labels
is_master_or_release = branch == "master" or branch.startswith("release/v")

skip_circleci = False
if pr_base_ref:
   ret = subprocess.call([".circleci/no_circleci_changes.sh", f"{pr_base_ref}..HEAD"], shell=False)
   if ret == 1:
       # Only GitLab-related files have changed, just skip Circle CI jobs.
       skip_circleci = True

if is_master_or_release or run_all:
    all_jdks = ALWAYS_ON_JDKS | MASTER_ONLY_JDKS
else:
    all_jdks = ALWAYS_ON_JDKS | (MASTER_ONLY_JDKS & labels)
nocov_jdks = [j for j in all_jdks if j != "8"]
# specific list for debugger project because J9-based JVM have issues with local vars
# so need to test at least against one J9-based JVM
all_debugger_jdks = all_jdks | {"semeru8"}

# Is this a nightly or weekly build? These environment variables are set in
# config.yml based on pipeline parameters.
is_nightly = os.environ.get("CIRCLE_IS_NIGHTLY", "false") == "true"
is_weekly = os.environ.get("CIRCLE_IS_WEEKLY", "false") == "true"
is_regular = not is_nightly and not is_weekly

# Use git changes detection on PRs
use_git_changes = not run_all and not is_master_or_release and is_regular

vars = {
    "is_nightly": is_nightly,
    "is_weekly": is_weekly,
    "is_regular": is_regular,
    "all_jdks": all_jdks,
    "all_debugger_jdks": all_debugger_jdks,
    "nocov_jdks": nocov_jdks,
    "flaky": "flaky" in labels or "all" in labels,
    "docker_image_prefix": "" if is_nightly else f"{DOCKER_IMAGE_VERSION}-",
    "use_git_changes": use_git_changes,
    "pr_base_ref": pr_base_ref,
    "skip_circleci": skip_circleci,
    "ssi_smoke": is_regular and is_master_or_release
}

print(f"Variables for this build: {vars}")

loader = jinja2.FileSystemLoader(searchpath=SCRIPT_DIR)
env = jinja2.Environment(loader=loader, trim_blocks=True)
tpl = env.get_template(TPL_FILENAME)
out = tpl.render(**vars)

with open(GENERATED_CONFIG_PATH, "w", encoding="utf-8") as f:
    f.write(out)
