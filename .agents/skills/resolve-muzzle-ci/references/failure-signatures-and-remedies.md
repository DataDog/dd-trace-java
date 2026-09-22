# Muzzle failure signatures and remedies

Use the first causal exception and the artifact-specific evidence together. The final Gradle task
failure is often only a wrapper.

## Repository infrastructure

Identify the repository host from the failing log and configuration. Muzzle's default version
resolver uses `MAVEN_REPOSITORY_PROXY` when set (for example, a Depot or MagicMirror endpoint),
otherwise Maven Central. This applies locally as well as in CI; inspect the setting rather than
assuming that local runs bypass the proxy. Directive-specific repositories may also participate.
Check Gradle's repositories separately for application-classpath resolution.

Strong transient signals include:

- `Muzzle version range resolution failed` with incomplete bounds, timeouts, connection resets,
  DNS/TLS failures, HTTP 429, or HTTP 5xx;
- failures from a configured `MAVEN_REPOSITORY_PROXY`, MagicMirror, or Depot host affecting several
  unrelated coordinates;
- the same unchanged task passing on retry, or a retry failing on a different unrelated artifact;
- no `FAILED MUZZLE VALIDATION` mismatch after an application classpath was assembled.

The current resolver makes immediate attempts followed by 5s, 10s, and 30s backoff retries and its
terminal diagnostic says when range metadata may be incomplete. Do not compensate for this class of
failure with a build-file change.

A 404 is not automatically transient. If the exact coordinate is consistently absent from the
artifact's authoritative repository, or a published POM consistently names a nonexistent
transitive artifact, classify it as a defective publication instead. A proxy-only miss does not
establish that the upstream publication is missing. When access permits, compare the same coordinate
with its authoritative repository without changing the build's repository settings.

The version scan reduces large ranges and may not select the same middle version on each run.
Therefore a green aggregate rerun is supporting evidence only when the exact previously failing
generated task/version ran again.

## Defective publications

Use `skipVersions` when the tested release itself is isolated and defective. Evidence should show
the exact version is missing, malformed, half-published, or has a POM that cannot produce a valid
classpath, while the surrounding version line remains valid. Existing comments such as `missing in
Maven Central` or `half propagated` describe historical publication failures, not necessarily the
endpoint used by today's CI. Verify the current authoritative publication and proxy responses before
reusing that remedy. A POM referencing a nonexistent Jetty artifact is another publication defect.

Use `excludeDependency` when only an irrelevant transitive dependency is unavailable or broken.
Before excluding it, check generated/advice references, helper bytecode and supertypes, explicit
additional references, and class-loader matching. If the instrumentation needs it, exclusion is not
a valid resolution: add the valid runtime dependency, adapt the instrumentation, or narrow support.

Distinguish these cases:

```text
bad root release only              -> skipVersions += '<version>'
unused broken transitive artifact  -> excludeDependency '<group>:<module>'
required dependency absent         -> fix classpath/publication assumptions or narrow support
```

## Compatibility validation

`FAILED MUZZLE VALIDATION: <instrumentation> mismatches:` followed by missing classes, methods,
fields, incompatible flags, or `-- classloader mismatch` means resolution succeeded and the pass
contract is false for that application classpath. Find the first version that changes the referenced
API and decide whether to support it:

- Restore support with version-tolerant instrumentation plus tests when the integration is meant to
  cover the new release.
- Cap the range at the first incompatible version when this module no longer supports that API line,
  normally using an exclusive upper bound such as `[min,firstBroken)`.
- Split or use a sibling module when both API lines should remain supported but require different
  advice or helpers.

`MUZZLE PASSED <instrumentation> BUT FAILURE WAS EXPECTED` means a `fail` directive or generated
inverse assertion is wrong. Check sibling coverage and whether the declared minimum is a real API
boundary. Do not create artificial mismatches to preserve a false inverse expectation.

`FAILED HELPER INJECTION` is also a real validation failure. Inspect helper ordering, missing helper
classes/supertypes, and class-loader visibility. A range cap is justified only if the failure begins
at a genuine library compatibility boundary.

## Release-age scenarios

- **Established release, unexpected failure:** a version published over a month ago previously
  passed, but now several unrelated coordinates fail with Depot timeouts or 5xx responses. Classify
  this from the repository errors and retry the failed job once when authorized. If instead its
  resolved classpath reports a concrete mismatch, inspect instrumentation and transitive dependency
  changes; an old release can still expose a real defect.
- **Fresh release, first failure:** a version published in the last few days resolves successfully
  but lacks a class present in the preceding release. Compare the artifacts to establish the API
  boundary, then restore support or cap the range below that boundary. If only the proxy lacks the
  fresh artifact, investigate propagation first. Use `skipVersions` only for an isolated defective
  publication, not simply because a release is new.
