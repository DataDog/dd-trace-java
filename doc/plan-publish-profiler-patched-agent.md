# Implementation Plan: publish-profiler-patched-agent-to-s3

Source design: `doc/design-publish-profiler-patched-agent.md`

---

## Scope

One file changes: `.gitlab-ci.yml`.

Insert a new job `publish-profiler-patched-agent-to-s3` immediately after the
`publish-artifacts-to-s3` job (currently ending at line 435). No other files
are modified.

---

## Tasks

### Task 1 — Insert the new job into `.gitlab-ci.yml`

**File**: `.gitlab-ci.yml`
**After**: line 435 (the closing line of `publish-artifacts-to-s3`)
**Action**: insert the following YAML block, preserving two blank lines between
jobs (consistent with the rest of the file).

```yaml
publish-profiler-patched-agent-to-s3:
  image: registry.ddbuild.io/images/base/gbi-ubuntu_2204:release
  stage: publish
  needs: [ build ]
  dependencies: [ build ]
  timeout: 10 minutes
  variables:
    AWS_ACCESS_KEY_ID: $AWS_ACCESS_KEY_ID
    AWS_SECRET_ACCESS_KEY: $AWS_SECRET_ACCESS_KEY
    AWS_DEFAULT_REGION: us-east-1
  before_script:
    - aws --version 2>/dev/null || pip install --quiet awscli==1.36.40
    - export GH_TOKEN="${GITHUB_TOKEN}"
  script:
    - source upstream.env
    - export VERSION="${UPSTREAM_TRACER_VERSION%~*}"
    - export AGENT_JAR="${CI_PROJECT_DIR}/workspace/dd-java-agent/build/libs/dd-java-agent-${VERSION}.jar"
    - export PATCHED_JAR="${CI_PROJECT_DIR}/workspace/dd-java-agent/build/libs/dd-java-agent-profiler.jar"
    - git clone --depth 1 https://oauth2:${GITHUB_TOKEN}@github.com/DataDog/java-profiler.git java-profiler
    - ARCH=$(uname -m); case "${ARCH}" in x86_64) DDPROF_ARCH="linux-x86_64" ;; aarch64) DDPROF_ARCH="linux-arm64" ;; *) echo "Unsupported architecture ${ARCH}"; exit 1 ;; esac
    - gh release download --repo DataDog/java-profiler --pattern "ddprof*${DDPROF_ARCH}*.jar" --dir ./ddprof-release
    - export DDPROF_JAR=$(ls ./ddprof-release/ddprof*${DDPROF_ARCH}*.jar)
    - '[ "$(echo "${DDPROF_JAR}" | wc -l)" -eq 1 ] || { echo "Expected exactly one ddprof jar for ${DDPROF_ARCH}, got: ${DDPROF_JAR}"; exit 1; }'
    - bash java-profiler/patch-dd-java-agent.sh "${AGENT_JAR}" "${DDPROF_JAR}" "${PATCHED_JAR}"
    - aws s3 cp "${PATCHED_JAR}" s3://dd-trace-java-builds/${CI_COMMIT_REF_NAME}/dd-java-agent-profiler.jar
    - aws s3 cp "${PATCHED_JAR}" s3://dd-trace-java-builds/${CI_PIPELINE_ID}/dd-java-agent-profiler.jar
    - |
      printf '{"S3 Links":[{"external_link":{"label":"Public Link to dd-java-agent-profiler.jar","url":"https://s3.us-east-1.amazonaws.com/dd-trace-java-builds/%s/dd-java-agent-profiler.jar"}}]}' \
        "${CI_PIPELINE_ID}" > links-profiler.json
  artifacts:
    paths:
      - ${CI_PROJECT_DIR}/workspace/dd-java-agent/build/libs/dd-java-agent-profiler.jar
    reports:
      annotations:
        - links-profiler.json
```

#### Verification checklist

- [ ] Job appears after `publish-artifacts-to-s3` in the `publish` stage
- [ ] `needs: [ build ]` and `dependencies: [ build ]` are both present
- [ ] `timeout: 10 minutes` is set
- [ ] `before_script` installs `awscli` conditionally and exports `GH_TOKEN`
- [ ] Arch detection uses `uname -m` and covers `x86_64` and `aarch64`; exits 1 for unknown arch
- [ ] Exact-one-jar guard is present after `ls`
- [ ] Both S3 upload paths (`${CI_COMMIT_REF_NAME}` and `${CI_PIPELINE_ID}`) are present
- [ ] `artifacts.paths` lists the patched jar
- [ ] `artifacts.reports.annotations` lists `links-profiler.json`
- [ ] YAML is syntactically valid (run `yamllint` or `gitlab-ci-lint`)

---

## Open Questions (must resolve before merging)

These are carried over from the design document and block a clean merge:

| # | Question | Where to look |
|---|----------|---------------|
| OQ1 | Exact path of `patch-dd-java-agent.sh` in java-profiler | `git clone --depth 1` the repo and `find . -name patch-dd-java-agent.sh` |
| OQ2 | Script argument order / flags | Read the script header/usage comment |
| OQ3 | Release artifact name pattern (`ddprof*${ARCH}*.jar` correct?) | `gh release view --repo DataDog/java-profiler` |
| OQ4 | `DataDog/java-profiler` public or private | `gh repo view DataDog/java-profiler` |
| OQ5 | Does `gbi-ubuntu_2204:release` already have `aws`? | Run `aws --version` in a throwaway job or inspect the image manifest |

The `before_script` conditional (`aws --version 2>/dev/null || pip install`) means
OQ5 is handled safely at runtime regardless. OQ1–OQ3 must be resolved by manually
inspecting the repo before this PR is merged.

---

## Validation

1. **YAML lint**: `yamllint .gitlab-ci.yml` — must pass with no errors.
2. **GitLab CI lint**: push to a feature branch and use the GitLab CI Lint UI
   (`/ci/lint`) to confirm the job graph is valid and the new job appears in the
   `publish` stage with `build` as its dependency.
3. **Dry-run on branch**: trigger the pipeline on a non-master branch; confirm
   the job runs, the patched jar is produced, and S3 uploads succeed.
