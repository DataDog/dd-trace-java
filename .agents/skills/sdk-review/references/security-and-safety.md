# Security & Safety

Secrets and sensitive configuration, CI and supply chain, PII, and safety inside customer processes.

**When planning:** take the rules below into account when designing the change.

**When reviewing:** also inspect commit messages and the PR description. Report a finding when the diff matches a rule's `Flag:` line, or when it has a clear security or safety problem that no rule covers (mark it `no rule`), with the leak path (source to sink).

## 1. Secrets and sensitive data

### SEC-1.1: Set `"sensitive": true` in `supported-configurations.json` for credential and secret keys
- Flag: a new `metadata/supported-configurations.json` entry for an API key, app key, token, password or auth header value without `"sensitive": true`, so configuration telemetry reports its value. See [add_new_configurations.md](../../../../docs/add_new_configurations.md#adding-a-configuration).
- ✅ `"DD_APP_KEY": [{ "version": "A", "type": "string", "default": null, "aliases": [], "sensitive": true }]`

### SEC-1.2: Never write API keys, auth or cookie headers, or credentialed URLs to logs, span tags or telemetry
- Flag: a `log.*`/`setTag` argument holding an API key, an `Authorization`/`Cookie`/`DD-API-KEY` header value, all extracted request headers, or a URL with user-info, at any level. Also flag leftover debugging logs added while developing the change.
- ❌ `log.info("Extracted headers: {}", headers);`
- ✅ `log.debug("Extracted trace context from {} headers", headers.size());`

### SEC-1.3: Keep credentials out of cacheable Gradle task outputs
- Flag: a cacheable task that writes a credential into a generated resource, archive or test report, or that keeps a credential set for tasks that run after the one that needs it. See [how_to_work_with_gradle.md](../../../../docs/how_to_work_with_gradle.md#secrets-in-cached-outputs).

### SEC-1.4: Keep customer names, identifying details and customer code out of diffs, commits and PR text
- Flag: a customer or company name, PII, or pasted customer code in a test name, comment, sample, commit message or PR description.

## 2. CI and supply chain

### SEC-2.1: Make every CI `curl` fail on HTTP errors, retry, and propagate failure
- Flag: a `curl` in `.gitlab-ci.yml`, `.gitlab/`, `.github/`, a script they call, or a Dockerfile they build, without `-f`/`--fail` or `--retry`, or piped straight into `bash`/`sh`. See [CONTRIBUTING.md](../../../../CONTRIBUTING.md#ci-scripts).
- Not when: the script checks the HTTP status itself (`-w "%{http_code}"`); it still needs `--retry`.
- ❌ `curl -sSL "$url" | bash`
- ✅ `curl -fsSL --retry 5 --retry-delay 2 --retry-connrefused -o install.sh "$url" && bash install.sh`

### SEC-2.2: Pin GitHub Actions to a full commit SHA with a version comment, and declare third-party actions in the allow list
- Flag: `uses: owner/action@<tag-or-branch>`, or a new action outside `actions/`/`github/` without a note to add it to the repository's allowed actions. See [the workflows README](../../../../.github/workflows/README.md#maintenance).
- ❌ `uses: actions/checkout@v4`
- ✅ `uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # 7.0.1`

### SEC-2.3: Scope workflow `permissions:` per job, and get cross-repository tokens from dd-octo-sts
- Flag: a workflow-level or `write-all` `permissions:` block, a job permission no step uses, or a new PAT secret where a `.github/chainguard/*.sts.yaml` policy and `DataDog/dd-octo-sts-action` would do.

## Don't flag
- Context or scope structures whose growth is bounded by the call stack (nested activations), raised as unbounded memory growth in customer processes.
