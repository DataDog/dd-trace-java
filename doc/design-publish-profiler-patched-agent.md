# Design: Publish Profiler-Patched Agent to S3

## Problem Statement

The existing `publish-artifacts-to-s3` job (`.gitlab-ci.yml:406`) uploads the stock
`dd-java-agent.jar` to S3 on every master push. We want to also publish a variant
that has been patched with the **latest released `java-profiler`** native libraries,
producing `dd-java-agent-profiler.jar` alongside the original artifact.

---

## Background

### How ddprof is currently embedded

The stock agent embeds ddprof at **build time**:
- `gradle/libs.versions.toml:12` pins `ddprof = "1.44.0"`
- `dd-java-agent/ddprof-lib/build.gradle` declares `com.datadoghq:ddprof` as a
  dependency and shadows its content (native `.so` files, services metadata)
  into the agent jar under the `META-INF/native-libs/` namespace.

The patching workflow replaces that embedded native library with a newer
(post-release) version **without rebuilding** the agent, which is the fast path
used for profiler-forward testing.

### `patch-dd-java-agent.sh`

This script lives in the **`DataDog/java-profiler`** GitHub repository. It takes:
- an existing `dd-java-agent.jar`
- a `ddprof.jar` (the artifact released by java-profiler)

and produces a patched jar with the new native libraries swapped in. The exact
signature needs to be confirmed by reading the script after cloning, but the
canonical invocation is expected to be something like:

```sh
./patch-dd-java-agent.sh <dd-java-agent.jar> <ddprof.jar> <output.jar>
```

---

## Design

### 1. New GitLab CI job: `publish-profiler-patched-agent-to-s3`

A **new, independent job** in `.gitlab-ci.yml` in the `publish` stage, parallel
to (not extending) the existing `publish-artifacts-to-s3` job.

Keeping it separate means:
- A failure in patching does not block the primary S3 upload.
- It can be retried independently.
- The responsibility boundary is clear.

### 2. Trigger condition

Same as `publish-artifacts-to-s3`: no explicit `rules` block → runs on every
pipeline. If restriction to master + release branches is desired, add:

```yaml
rules:
  - if: '$POPULATE_CACHE'
    when: never
  - *master_only
```

The `&master_only` anchor is defined at `.gitlab-ci.yml` line 130:

```yaml
.master_only: &master_only
  - if: $CI_COMMIT_BRANCH == "master"
    when: on_success
```

Mirror the existing `deploy_snapshot_with_ddprof_snapshot` pattern for release
branches if needed later.

### 3. Image

The existing `amazon/aws-cli:2.4.29` image does not have `git` or `gh`.

Options (in preference order):

| Option | Image | Notes |
|--------|-------|-------|
| A (preferred) | `registry.ddbuild.io/images/base/gbi-ubuntu_2204:release` | Already used by `deploy_artifacts_to_github` (line 1328). Has `git`, `gh`, `curl`, `unzip`. Add `awscli` via `pip` or a side-step with `aws` already in PATH via the CI base. |
| B | `registry.ddbuild.io/images/dd-octo-sts-ci-base:2025.06-1` | Has `gh` + AWS support. |
| C | Custom image with both `aws-cli` and `git` | Requires a new image PR in DataDog/images. More work. |

**Recommendation: Option A** — `gbi-ubuntu_2204:release` is already in use for
GitHub publishing jobs in this file and avoids a new image dependency.

### 4. Cloning `DataDog/java-profiler`

Use a **shallow clone** (depth 1) to get just the tip of the default branch:

```sh
git clone --depth 1 https://github.com/DataDog/java-profiler.git java-profiler
```

If the repo is private, authentication is required. Use a GitLab CI variable
(e.g. `$GITHUB_TOKEN`, already used in other jobs in this file) with HTTPS:

```sh
git clone --depth 1 https://oauth2:${GITHUB_TOKEN}@github.com/DataDog/java-profiler.git java-profiler
```

**Verify the script path** after first clone: likely `java-profiler/patch-dd-java-agent.sh`
or `java-profiler/tools/patch-dd-java-agent.sh`.

### 5. Getting the latest `ddprof.jar`

Two options:

#### Option A — GitHub Releases of `java-profiler` (recommended)

```sh
# Select the jar matching the current runner architecture (x86_64 or arm64).
ARCH=$(uname -m)
case "${ARCH}" in
  x86_64)  DDPROF_ARCH="linux-x86_64" ;;
  aarch64) DDPROF_ARCH="linux-arm64" ;;
  *)       echo "Unsupported architecture: ${ARCH}"; exit 1 ;;
esac
gh release download --repo DataDog/java-profiler \
  --pattern "ddprof*${DDPROF_ARCH}*.jar" \
  --dir ./ddprof-release
DDPROF_JAR=$(ls ./ddprof-release/ddprof*${DDPROF_ARCH}*.jar)
# Fail loudly if no jar or more than one jar was matched.
[ "$(echo "${DDPROF_JAR}" | wc -l)" -eq 1 ] || { echo "Expected exactly one ddprof jar for ${DDPROF_ARCH}, got: ${DDPROF_JAR}"; exit 1; }
```

This is the canonical release artifact that `patch-dd-java-agent.sh` is designed
to consume. It is version-aligned with the script in the same repo tag.

`GITHUB_TOKEN` is already set in CI for other jobs. The `gh` CLI accepts both
`GH_TOKEN` and `GITHUB_TOKEN`; to be unambiguous the job exports `GH_TOKEN`
from `GITHUB_TOKEN` in `before_script` (see job sketch below).

#### Option B — Maven Central

```sh
DDPROF_VERSION=$(curl -s "https://search.maven.org/solrsearch/select?q=g:com.datadoghq+AND+a:ddprof&rows=1&wt=json" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['response']['docs'][0]['latestVersion'])")
curl -Lo ddprof.jar \
  "https://repo1.maven.org/maven2/com/datadoghq/ddprof/${DDPROF_VERSION}/ddprof-${DDPROF_VERSION}.jar"
```

Drawback: Maven Central lags GitHub releases by hours/days. The unclassified jar
(`ddprof-${DDPROF_VERSION}.jar`) is a POM-only artifact; the actual native-library
jar requires a classifier. For the amd64 CI runner use `-linux-x86_64`:

```sh
curl -Lo ddprof.jar \
  "https://repo1.maven.org/maven2/com/datadoghq/ddprof/${DDPROF_VERSION}/ddprof-${DDPROF_VERSION}-linux-x86_64.jar"
```

For arm64 runners substitute `-linux-arm64`. Because the correct classifier depends
on the runner architecture and must be kept in sync with future runner changes,
Option A (GitHub releases with `--pattern "ddprof*.jar"`) is strongly preferred.

**Recommendation: Option A** — GitHub releases are the primary distribution
channel for java-profiler and are what the patch script is built around.

### 6. Running the patch script

`upstream.env` is produced by the `build` job (`.gitlab-ci.yml`, `build:` block).
That job appends `UPSTREAM_TRACER_VERSION=<version>` to `upstream.env` and lists **both**
`upstream.env` and `workspace/dd-java-agent/build/libs/*.jar` under `artifacts.paths`,
making them available to downstream jobs. Because the new job declares both `needs: [ build ]`
and `dependencies: [ build ]`, GitLab downloads the `build` artifacts before the script
runs, so both `upstream.env` and the agent jar are present on disk without any additional
configuration. (Without an explicit `dependencies:` key, GitLab's default is to download
artifacts from **all** jobs listed in `needs:`, which is the same outcome here, but the
explicit key makes the intent unambiguous.)

**Note**: the `build` job unpacks the agent jar under the `workspace/` subdirectory
(`.gitlab-ci.yml` line 347: `workspace/dd-java-agent/build/libs/*.jar`), not directly
under `CI_PROJECT_DIR`. The `AGENT_JAR` path below must therefore be adjusted to
`${CI_PROJECT_DIR}/workspace/dd-java-agent/build/libs/dd-java-agent-${VERSION}.jar`.

```sh
source upstream.env
VERSION="${UPSTREAM_TRACER_VERSION%~*}"
# ${CI_PROJECT_DIR} is the absolute path to the cloned repository in a GitLab CI job
# (see https://docs.gitlab.com/ee/ci/variables/predefined_variables.html).
# The `build` job artifact path is workspace/dd-java-agent/build/libs/*.jar
# (.gitlab-ci.yml artifacts.paths line 347), so the jar is under the workspace/ subdir.
AGENT_JAR="${CI_PROJECT_DIR}/workspace/dd-java-agent/build/libs/dd-java-agent-${VERSION}.jar"
PATCHED_JAR="${CI_PROJECT_DIR}/workspace/dd-java-agent/build/libs/dd-java-agent-profiler.jar"

bash java-profiler/patch-dd-java-agent.sh "${AGENT_JAR}" "${DDPROF_JAR}" "${PATCHED_JAR}"
```

**Caveat**: the exact signature of `patch-dd-java-agent.sh` must be confirmed
from the actual script after cloning. Update the invocation accordingly.

### 7. S3 upload

Upload `dd-java-agent-profiler.jar` to the same two paths as the existing agent:

```sh
aws s3 cp "${PATCHED_JAR}" s3://dd-trace-java-builds/${CI_COMMIT_REF_NAME}/dd-java-agent-profiler.jar
aws s3 cp "${PATCHED_JAR}" s3://dd-trace-java-builds/${CI_PIPELINE_ID}/dd-java-agent-profiler.jar
```

Add an annotation link in `links-profiler.json` for the profiler-patched jar.

---

## Full job sketch

```yaml
publish-profiler-patched-agent-to-s3:
  image: registry.ddbuild.io/images/base/gbi-ubuntu_2204:release
  stage: publish
  needs: [ build ]
  dependencies: [ build ]
  timeout: 10 minutes  # shallow clone + single release asset download; 10 min is ample
  # No explicit rules → runs on every pipeline (same behaviour as
  # publish-artifacts-to-s3). Add the following block if restriction is needed:
  #   rules:
  #     - *master_only
  variables:
    # AWS credentials required for `aws s3 cp` to s3://dd-trace-java-builds/.
    # These mirror the variables used by the existing `publish-artifacts-to-s3` job.
    # They must be defined as GitLab CI/CD project variables (Settings → CI/CD →
    # Variables) and are injected automatically by GitLab when the job runs.
    AWS_ACCESS_KEY_ID: $AWS_ACCESS_KEY_ID
    AWS_SECRET_ACCESS_KEY: $AWS_SECRET_ACCESS_KEY
    AWS_DEFAULT_REGION: $AWS_DEFAULT_REGION   # e.g. "us-east-1"
  before_script:
    # Install AWS CLI only if not already present in the image (resolves OQ 5)
    - aws --version 2>/dev/null || pip install --quiet awscli==1.36.40
    # Export GH_TOKEN so that `gh` CLI is authenticated regardless of which variable
    # name the CI environment exposes (gh accepts both GH_TOKEN and GITHUB_TOKEN,
    # but being explicit avoids silent auth failures).
    - export GH_TOKEN="${GITHUB_TOKEN}"
  script:
    # --- 1. Derive agent version from build output ---
    # upstream.env is produced by the `build` job and inherited via `needs: [ build ]`
    # artifacts. It contains UPSTREAM_TRACER_VERSION set to the agent jar version string.
    - source upstream.env
    - export VERSION="${UPSTREAM_TRACER_VERSION%~*}"
    # CI_PROJECT_DIR is set by GitLab to the absolute path of the cloned repository.
    # The build job artifact path is workspace/dd-java-agent/build/libs/*.jar, so the
    # jar is unpacked under the workspace/ subdirectory of CI_PROJECT_DIR.
    - export AGENT_JAR="${CI_PROJECT_DIR}/workspace/dd-java-agent/build/libs/dd-java-agent-${VERSION}.jar"
    - export PATCHED_JAR="${CI_PROJECT_DIR}/workspace/dd-java-agent/build/libs/dd-java-agent-profiler.jar"

    # --- 2. Shallow-clone java-profiler for the patch script ---
    - git clone --depth 1 https://oauth2:${GITHUB_TOKEN}@github.com/DataDog/java-profiler.git java-profiler

    # --- 3. Download latest ddprof.jar from java-profiler GitHub releases ---
    # Narrow the pattern to the runner's architecture to avoid silently picking the
    # wrong jar when multiple per-arch jars are present in the release.
    - ARCH=$(uname -m); case "${ARCH}" in x86_64) DDPROF_ARCH="linux-x86_64" ;; aarch64) DDPROF_ARCH="linux-arm64" ;; *) echo "Unsupported architecture ${ARCH}"; exit 1 ;; esac
    - gh release download --repo DataDog/java-profiler --pattern "ddprof*${DDPROF_ARCH}*.jar" --dir ./ddprof-release
    - export DDPROF_JAR=$(ls ./ddprof-release/ddprof*${DDPROF_ARCH}*.jar)
    - '[ "$(echo "${DDPROF_JAR}" | wc -l)" -eq 1 ] || { echo "Expected exactly one ddprof jar for ${DDPROF_ARCH}, got: ${DDPROF_JAR}"; exit 1; }'

    # --- 4. Patch the agent ---
    # NOTE: confirm exact script path and argument order from the cloned repo
    - bash java-profiler/patch-dd-java-agent.sh "${AGENT_JAR}" "${DDPROF_JAR}" "${PATCHED_JAR}"

    # --- 5. Upload to S3 ---
    - aws s3 cp "${PATCHED_JAR}" s3://dd-trace-java-builds/${CI_COMMIT_REF_NAME}/dd-java-agent-profiler.jar
    - aws s3 cp "${PATCHED_JAR}" s3://dd-trace-java-builds/${CI_PIPELINE_ID}/dd-java-agent-profiler.jar

    # --- 6. Annotate pipeline with public links ---
    # printf is used instead of a heredoc to avoid shell-vs-YAML variable
    # expansion ambiguity: GitLab CI executes `- |` blocks via bash, so
    # unquoted heredoc delimiters do expand CI variables, but printf makes
    # the intent explicit and sidesteps any editor/linter confusion.
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

---

## Open Questions

1. **Script path in java-profiler**: Is it `patch-dd-java-agent.sh` at the repo
   root, or in a subdirectory (e.g. `tools/`, `scripts/`)? Confirm after cloning.

2. **Script argument order**: Confirm whether the signature is
   `script <agent> <ddprof> <output>` or different (e.g. `--agent`, `--profiler`,
   `--output` flags).

3. **ddprof release artifact name**: Does the GitHub release publish `ddprof-*.jar`
   or a differently named artifact? Check `gh release view --repo DataDog/java-profiler`.

4. **Authentication for java-profiler**: Is the repo public or private? If private,
   `GITHUB_TOKEN` needs read access to it (scope: `repo` or `contents:read`).

5. **`gbi-ubuntu_2204:release` has AWS CLI?**: Confirm via `aws --version` in a
   test job, or switch to a combined image. Alternatively keep the `aws-cli` image
   and install `git`+`gh` as a `before_script` step (only two packages).

6. **Multiple native architectures**: `ddprof` ships per-arch jars (x86_64, arm64).
   The current upload target is `arch:amd64`. If arm64 patching is needed later,
   a matrix job should be introduced.

7. **Rules alignment**: Should this job also run on release branches (`v*.*.* `)?
   The current `publish-artifacts-to-s3` has no explicit rules restriction.

---

## Recommended Implementation Order

1. Read `patch-dd-java-agent.sh` from the java-profiler repo to confirm the
   interface (resolves OQ 1 & 2).
2. Confirm the ddprof release artifact name (`gh release view`) (OQ 3).
3. Confirm `GITHUB_TOKEN` access (OQ 4).
4. Choose image and confirm AWS CLI availability (OQ 5).
5. Add the job to `.gitlab-ci.yml` after `publish-artifacts-to-s3`.
6. Test on a branch before merging to master.
