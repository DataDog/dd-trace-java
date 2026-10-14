# Agent context for build-logic

Read the [root Gradle instructions](../AGENTS.md#gradle-changes), including the mandatory
`gradle-best-practices` prerequisite, before changing build behaviour. Use the relevant
sections of the [Gradle guide](../docs/how_to_work_with_gradle.md) for explanations.

## Scope and boundaries

`build-logic` is an included build, registered through `pluginManagement.includeBuild`.
It owns project-facing conventions and plugins for smoke-test applications, tag registration,
and testcontainers.

- Keep settings, repositories, plugin resolution, and dependency declarations explicit in
  this build. Root-build configuration does not automatically propagate here. Reuse the
  existing import of `../gradle/libs.versions.toml` and preserve repository proxy behaviour.
- Place new project-facing conventions here when they fit the existing architecture.
  Keep module-specific choices in consuming modules and expose only the needed extension
  properties. Follow existing placement for changes to existing plugins.
- Sharing a source root does not justify coupling plugin implementations. Follow the
  root guardrails for explicit dependencies and narrow public contracts.
- Preserve each subproject's JVM target and worker requirements. Smoke-test fixtures run
  nested builds with older Gradle versions; preserve their Java 21 test launcher.
- Keep optional plugin integrations independent of application order. Check both Groovy
  and Kotlin consumers when changing a convention or extension API.
- Keep plugin dependencies on the build-logic classpath. Verify packaging when changing
  dependencies so consumer application or agent artifacts do not gain build-only libraries.

## Verification

Use existing JUnit 5 tests and Kotlin fixtures. Reuse the affected module's TestKit setup,
including repository proxy handling and environment isolation. Keep language migrations
outside the requested change. Verify plugin integration independently and in composition
where applicable, plus a representative consuming module.

Start with the affected module's tests, for example:

```shell
./gradlew -p build-logic :smoke-test:test
```

For changes spanning modules, use `./gradlew :build-logic:check` from the repository root.
Run the applicable formatting checks, inspect the diff, and run `git diff --check`.
