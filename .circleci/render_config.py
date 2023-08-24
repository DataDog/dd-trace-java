#!/usr/bin/env python3

import os
import os.path
import time

import jinja2
import requests

SCRIPT_DIR = os.path.dirname(__file__)

TPL_FILENAME = "config.continue.yml.j2"
OUT_FILENAME = "config.continue.yml"
GENERATED_CONFIG_PATH = os.path.join(SCRIPT_DIR, OUT_FILENAME)

# JDKs that will run on every pipeline.
ALWAYS_ON_JDKS = {"8", "11", "17"}
# And these will run only in master and release/ branches.
MASTER_ONLY_JDKS = {
    "ibm8",
    "oracle8",
    "semeru8",
    "zulu8",
    "semeru11",
    "zulu11",
    "semeru17",
    "ubuntu17",
}

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
            print(f"Request filed: {e}")
            time.sleep(1)
            continue
        data = resp.json()
        break

    labels = data.get("labels", [])
    labels = [l["name"] for l in labels]
    labels = {
        l.replace("run-tests: ", "") for l in labels if l.startswith("run-tests: ")
    }
else:
    labels = set()


branch = os.environ.get("CIRCLE_BRANCH", "")
if branch == "master" or branch.startswith("release/v") or "all" in labels:
    all_jdks = ALWAYS_ON_JDKS | MASTER_ONLY_JDKS
else:
    all_jdks = ALWAYS_ON_JDKS | (MASTER_ONLY_JDKS & labels)
nocov_jdks = [j for j in all_jdks if j != "8"]

# Is this a nightly or weekly build? These environment variables are set in
# config.yml based on pipeline parameters.
is_nightly = os.environ.get("CIRCLE_IS_NIGHTLY", "false") == "true"
is_weekly = os.environ.get("CIRCLE_IS_WEEKLY", "false") == "true"
is_regular = not is_nightly and not is_weekly

vars = {
    "is_nightly": is_nightly,
    "is_weekly": is_weekly,
    "is_regular": is_regular,
    "all_jdks": all_jdks,
    "nocov_jdks": nocov_jdks,
    "flaky": branch == "master" or "flaky" in labels or "all" in labels,
}

print(f"Variables for this build: {vars}")

loader = jinja2.FileSystemLoader(searchpath=SCRIPT_DIR)
env = jinja2.Environment(loader=loader)
tpl = env.get_template(TPL_FILENAME)
out = tpl.render(**vars)

with open(GENERATED_CONFIG_PATH, "w", encoding="utf-8") as f:
    f.write(out)
