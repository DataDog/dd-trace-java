# Windows GitLab prototype

This directory contains an experimental Windows test job and its repo-local CI image.
The image contains MinGit and the Temurin 8, 11, 17, 21, and 25 JDK toolchains used by
the Gradle build. JDK 21 is the default daemon and test JVM. The base test scope runs
`:baseTest` on Java 21, split into the same four partitions as the existing `test_base`
job. A second, fully manual scope runs `:instrumentationTest` on Java 21 in eight
partitions. Both validate the execution model before moving the image to
`dd-trace-java-docker-build`.

## Running the prototype

1. Push the branch and open its GitLab pipeline.
2. Manually run `build-windows-ci-image`.
3. Wait for the image to be pushed to
   `registry.ddbuild.io/ci/dd-trace-java/dd-trace-java-windows-docker-build:prototype-alexeyk-gitlab-windows-tests`.
4. Run or retry the four `test-base-windows` matrix jobs.
5. Trigger the desired `test-inst-windows` partitions manually. Starting with `1/8`
   provides a small plumbing check before running the other seven partitions.

The image producer always overwrites this single mutable prototype tag and uses the
previous image as its Docker layer cache. Test jobs explicitly pull the tag before use,
so a long-lived Windows runner does not reuse a stale local copy.

The base test job is manual and non-blocking on feature branches. It runs automatically
but remains non-blocking on merge-queue branches and `master`. Instrumentation jobs are
manual and non-blocking on every ref during the initial rollout.

## Updating the image

Run `build-windows-ci-image` after changing anything under `image/`. The same prototype
tag is replaced after a successful build, which avoids accumulating per-experiment tags.
The test job fails with an instruction to run the producer when it cannot pull that tag.

## Caching

Test jobs read `.gradle/{wrapper,caches,notifications}` from a category-specific seed
cache that only protected refs write, then push a per-branch, per-partition cache on top.
A new feature branch therefore starts from the last protected-ref dependency set instead
of resolving everything separately in every partition.

## Notes on Windows

- All `docker` calls go through `Invoke-Native` in `ci-common.ps1`. PowerShell turns a
  native command's stderr into a terminating error under `$ErrorActionPreference = 'Stop'`,
  and docker writes progress and "manifest unknown" to stderr.
- The container gets `LongPathsEnabled` and `core.longpaths`; the deepest relocated build
  outputs under `C:\work\workspace\...` exceed the 260-character `MAX_PATH` default.
- Temurin publishes no JDK 21 newer than `21.0.12+8` for `windowsservercore-ltsc2025`,
  while 8, 11, 17, and 25 are current. Expect some failures on the Java 21 matrix to be
  JDK-version artifacts rather than Windows-specific.
- The initial instrumentation jobs do not set `CI_USE_TEST_AGENT`. The Linux Test Agent
  sidecar is not reachable from the explicitly launched Windows test container without
  additional networking support; instrumentation tests still use their in-memory writer
  for their primary assertions.
