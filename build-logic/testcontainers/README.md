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

Keep the dedicated container type and pass the property through its `DockerImageName`
constructor. The compatibility declaration lets it accept a mirrored image without
changing the resolved reference:

```java
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

CassandraContainer container = new CassandraContainer(
    DockerImageName.parse(System.getProperty("test.cassandra.image"))
        .asCompatibleSubstituteFor("cassandra"));
```

Each source set gets a `<sourceSet>ContainerImage` declaration method. Images follow
`implementation` configuration inheritance, so a `latestDepTest` suite extending
`testImplementation` inherits its images. The matching `Test` task, `forkedTest`,
and `<sourceSet>ForkedTest` companions receive the properties. A separate suite can
declare its own images with, for example, `integrationTestContainerImage(...)`.
Run IDE tests through Gradle, or supply the named image properties explicitly.

This plugin only fingerprints images. Container concurrency remains controlled by
the existing, explicit `usesService(testcontainersLimit)` declarations in module
build scripts; applying this plugin does not add or remove them.

## Resolution and test execution

For each selected test task with image declarations:

1. Apply the task's effective Docker Hub prefix to image names without an explicit
   registry. This selects the registry before resolving any digest.
2. Use Jib to fetch each tag's manifest from that registry and produce an immutable
   `registry/repository@sha256:...` reference. Jib does not download image layers or
   require a Docker daemon. Declarations already containing a digest skip this request.
3. Include the property names and resolved references in Gradle's test input
   fingerprint, before the up-to-date and build-cache checks.
4. If the test needs to run, pass the same references as `-D<property>=<reference>`
   arguments to its JVM. Testcontainers then uses Docker to pull and start those
   exact images. Cached test results require no container startup.

The resolver shares each effective image's result within one build. The next build
resolves moving tags again, even when reusing the configuration cache. Unchanged
references allow `UP-TO-DATE`/`FROM-CACHE`; changed digests select a different cache
entry. The registry and repository are also part of the input, so switching hosts
changes the fingerprint even if both registries return the same digest.

Moving tags need registry access even when Docker already has the image locally.
Resolution failure stops the test instead of trusting stale results. Unrelated
tasks and skipped tests perform no registry requests.

Configuration uses `configureEach` and a project-local provider rather than
`afterEvaluate`. Late declarations, configuration inheritance, resource directories
and task environment settings are captured when Gradle queries the provider.
Only that configuration is cached; the nested input getter resolves images before
test cache lookup on every build. Isolated Projects has not been tested.

## Local registries and CI mirrors

Without a prefix, the example declaration resolves `cassandra:4` from Docker Hub.
CI sets `TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX=registry.ddbuild.io/images/mirror/`
in the [test job configuration](../../.gitlab-ci.yml), giving this flow:

```text
Local: cassandra:4
       -> registry-1.docker.io/library/cassandra@sha256:...

CI:    cassandra:4
       -> registry.ddbuild.io/images/mirror/cassandra:4
       -> registry.ddbuild.io/images/mirror/cassandra@sha256:...
```

CI fingerprints the mirror's content, which may differ from Docker Hub. The test
receives the fully qualified reference, so Testcontainers does not apply the prefix
again. This follows Testcontainers' [image substitution rules](https://java.testcontainers.org/features/image_name_substitution/).

The prefix comes from the task's `TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX`, then
`hub.image.name.prefix` in the user's `.testcontainers.properties` and source-set
`testcontainers.properties`. Explicit task environment overrides and removals are
respected. Changing the inherited `TESTCONTAINERS_` environment variables invalidates
the configuration cache.

Explicit registry names bypass the prefix, including explicit Docker Hub hosts.
For example, Pub/Sub's `gcr.io` and WebSphere's `icr.io` declarations retain their
registries. Other mappings belong in the declaration:
[JDBC's SQL Server declaration](../../dd-java-agent/instrumentation/jdbc/build.gradle)
selects `mcr.microsoft.com/mssql/server:latest` locally and
`registry.ddbuild.io/images/mirror/sqlserver:latest` when `CI` is present. This
replaces runtime substitution so Gradle fingerprints the image the test will use.

Custom image substitutors and `*.container.image` overrides in these configuration
sources are rejected because they could replace the resolved digest at runtime.
Image substitution supplied by dependency JAR resources is not supported.

Private registries use Docker's `config.json` and credential helpers from the Gradle
process, honoring `DOCKER_CONFIG`. Remote registries require TLS; loopback registries
also permit local development certificates and HTTP.

Jib's dependencies are relocated inside the plugin JAR so older libraries exported
by `buildSrc` cannot override its HTTP client. Tests load that JAR through an
included build with an older HttpClient on the `buildSrc` classpath.

Only declared images are tracked. This migration covers Cassandra, Pub/Sub,
JDBC, Vert.x MySQL/PostgreSQL and WebSphere fixtures. Other fixtures and implicit
Testcontainers helpers such as Alpine/Ryuk require separate adoption.

## Verification

Run the hermetic registry and Gradle cache tests with:

```shell
build-brief ./gradlew -p build-logic :testcontainers:test :testcontainers:validatePlugins
```
