# Container images as test inputs

Apply this plugin only in modules that use containers:

```groovy
plugins {
  id 'dd-trace-java.testcontainers'
}

dependencies {
  testContainerImage(image('cassandra:4', 'test.cassandra.image'))
}
```

Read the property when constructing the container. Keep Testcontainers' compatibility
declaration when the image can come from a mirror:

```java
DockerImageName.parse(System.getProperty("test.cassandra.image"))
    .asCompatibleSubstituteFor("cassandra")
```

Each source set gets a `<sourceSet>ContainerImage` declaration method. Images follow
`implementation` configuration inheritance, so a `latestDepTest` suite extending
`testImplementation` inherits its images. The matching `Test` task, `forkedTest`,
and `<sourceSet>ForkedTest` companions receive the properties. A separate suite can
declare its own images with, for example, `integrationTestContainerImage(...)`.
Run IDE tests through Gradle, or supply the named image properties explicitly.

Tasks with declared or inherited images also consume the shared `testcontainersLimit`
service. `testcontainersMaxParallelUsages` controls the build-wide quota (default: 2).
The legacy convention registers the same service for modules that still declare
`usesService(testcontainersLimit)` explicitly. Unrelated suites do not consume it.
The limit applies to whole test tasks, not individual test methods or containers.

Configuration uses `configureEach` and a project-local provider rather than an
evaluation callback. Late declarations, configuration inheritance, resource directories
and task environment settings are captured when Gradle queries the provider.
Only that configuration is cached; registry resolution remains an execution-time input.
Isolated Projects has not been tested.

The service references are declared on nested task inputs with `@ServiceReference`.
CI selectors running during configuration should check
`ContainerImageArguments.containers.isPresent` in the task's `jvmArgumentProviders`.
Gradle's internal `requiredServices.isServiceRequired` may not yet include these
inferred references; retain its existing check for legacy explicit service users.

The plugin resolves each effective tag once per build, when Gradle snapshots test
inputs. An annotated JVM argument provider includes property names and immutable
`registry/repository@sha256:...` values in the test fingerprint, then passes those
same values to the JVM. Unchanged images allow `UP-TO-DATE`/`FROM-CACHE`; changed
images select a different cache entry. Configuration-cache reuse still refreshes
tags. Unrelated tasks and skipped tests perform no registry requests. Resolution
failure stops the test instead of trusting stale results.

Jib reads registry manifests without pulling layers or requiring a Docker daemon.
Its dependencies are relocated inside the plugin JAR so older libraries exported
by `buildSrc` cannot override its HTTP client. Tests load that JAR through an
included build with an older HttpClient on the `buildSrc` classpath.
Private registries use Docker's `config.json` and credential helpers, honoring
`DOCKER_CONFIG`. Remote registries require TLS; loopback registries also permit
local development certificates and HTTP.

Docker Hub substitution honors `TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX`, then the
user's `.testcontainers.properties` and source-set `testcontainers.properties`.
Explicit registry names bypass this prefix, matching Testcontainers. Other mappings
belong in the declaration; JDBC's SQL Server declaration selects its CI mirror there.
Custom image substitutors and `*.container.image` overrides in these configuration
sources are rejected because they could replace the resolved digest at runtime.
Image substitution supplied by dependency JAR resources is not supported.

Only declared images are tracked. This migration covers Cassandra, Pub/Sub,
JDBC, Vert.x MySQL/PostgreSQL and WebSphere fixtures. Other fixtures and implicit
Testcontainers helpers such as Alpine/Ryuk require separate adoption.

Run the hermetic registry and Gradle cache tests with:

```shell
build-brief ./gradlew -p build-logic :testcontainers:test :testcontainers:validatePlugins
```
