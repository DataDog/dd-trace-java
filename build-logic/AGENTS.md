# Working in build-logic

Use `gradle-best-practices` before changing Gradle code here. If it is missing or incomplete,
follow the [skill setup](../docs/how_to_work_with_gradle.md#gradle-skills) before editing.

This included build holds project conventions and the smoke-test, tag-registry and
testcontainers plugins. Its settings import `../gradle/libs.versions.toml` and configure
repository proxies separately from the main build.

The smoke-test plugin runs nested builds with older Gradle versions. Its tests use Java 21
for that reason; keep that launcher when changing the tests or toolchain configuration.

## Tests

The plugin tests use JUnit 5 and Kotlin. Reuse the module's TestKit setup, including its
repository proxy handling and environment isolation.

Run the tests for the module you changed, for example:

```shell
./gradlew -p build-logic :smoke-test:test
```

For changes across modules, `./gradlew :build-logic:check` runs the included build's checks
from the repository root.
