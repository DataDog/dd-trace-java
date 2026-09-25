# Container images as test inputs

Apply this plugin only in modules that use containers:

```kotlin
import datadog.buildlogic.testcontainers.image

plugins {
  id("dd-trace-java.testcontainers")
}

dependencies {
  testContainerImage(image("cassandra:4", "test.cassandra.image"))
}
```

> [!NOTE]
> `testContainerImage` is a configuration.
> Note the `image` function import.

Use `GenericContainer` or the dedicated container type and pass the relevant 
system property through its `DockerImageName` constructor. Note the compatibility 
declaration that let it accept a mirrored image without changing the resolved
reference:

```java
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

CassandraContainer container = new CassandraContainer(
    DockerImageName.parse(System.getProperty("test.cassandra.image"))
        .asCompatibleSubstituteFor("cassandra"));
```

`testContainerImage` is a dependency-scope configuration: it stores declarations
and is neither resolvable nor consumable. `image("image", "system property name")` 
creates a Gradle module dependency. 
Note that these declarations stay outside Java classpaths. Before test task runs, the plugin
resolves the images digest.

The plugin identifies a test task's source sets from its `testClassesDirs` and
reads their image configurations. Forked or differently named tasks running the
same compiled tests therefore inherit the same images. Additional images can be
declared in `<taskName>ContainerImage`.
In practice the plugin automatically creates an image configuration for each `*Implementation` 
configuration, including those added by JVM Test Suites. For example:

```kotlin
testing {
  suites {
    register<JvmTestSuite>("integrationTest") {
      useJUnitJupiter()
      project.dependencies {
        add("integrationTestContainerImage", image("redis:7-alpine", "test.redis.image"))
      }
    }
  }
}
```

With the repository's legacy Groovy `addTestSuite` helper, declare the image in
the project's `dependencies` block:

```groovy
addTestSuite('integrationTest')

dependencies {
  integrationTestContainerImage(image('redis:7-alpine', 'test.redis.image'))
}
```

Automatic inheritance follows Java dependency configurations: if
`latestDepTestImplementation` extends `testImplementation`, its tests also read
`testContainerImage`. The plugin maps each configuration in that hierarchy to its
image counterpart. It also reads parents declared with `extendsFrom` on image
configurations:

```kotlin
val databaseImages = configurations.dependencyScope("databaseImages")
configurations.testContainerImage {
  extendsFrom(databaseImages.get())
}
dependencies {
  add(databaseImages.name, image("postgres:16-alpine", "test.postgres.image"))
}
```

The plugin ensures there’s only one image per system property for a given test task. 
Declaring both `image("redis:7", "test.redis.image")` and `image("redis:8", "test.redis.image")`
for that task, directly or through inherited configurations, will fail.

Groovy DSL is declared the same `dependencies { testContainerImage(image('cassandra:4', 'test.cassandra.image')) }`.

## Resolution and test execution

The plugin resolves declared tags to immutable reference like `registry/repository@sha256:...`
before Gradle checks the build-cache for reusable test results. These references are
both test inputs and system property values, so actual code in the tests use the exact
images.

Like when java dependencies are declared with `+`, the tags are resolved on each invocation,
including when reusing the configuration cache. With a build cache this means unchanged 
references allow to safely reuse test results, while Gradle is blind when the image is 
resolving within test code.

> [!NOTE]
> Only explicitly declared images can be tracked. Implicit Testcontainers helpers such as
> Alpine/Ryuk are not included. This can't be avoided.

## Local registries and CI mirrors

This plugin honors `TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX` environment variable.

Locally, when asking for `cassandra:4` it is resolved from Docker Hub. 
However, on [CI](../../.gitlab-ci.yml), the env var `TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX=...` is set, 
so the plugin resolves and fingerprints from the prefix, instead, e.g.:

```text
Local: registry-1.docker.io/library/cassandra@sha256:...
CI:    registry.ddbuild.io/images/mirror/cassandra@sha256:...
```

More precisely:
- Prefix precedence: the test task's `TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX`, then
  `hub.image.name.prefix` in `~/.testcontainers.properties`, then
  `testcontainers.properties` in test classpath directories.
- Explicit registry hosts bypass the prefix. Other mappings belong in the image
  declaration, as in [JDBC's SQL Server example](../../dd-java-agent/instrumentation/jdbc/build.gradle).
- Private registries use Docker's `config.json` and credential helpers, honoring
  `DOCKER_CONFIG`. Remote registries require TLS; loopback registries also allow
  local development certificates and HTTP.

## Testcontainers configuration restrictions

Testcontainers supports [configuration properties and environment variables](https://java.testcontainers.org/features/configuration/)
that change its runtime behavior. In particular, its [image-substitution settings](https://java.testcontainers.org/features/image_name_substitution/)
can replace the images used when containers start.

> [!IMPORTANT]
> This plugin forbids custom image substitutors (`image.substitutor`) and all
> `*.container.image` overrides: **the test task fails when these settings are
> detected**. Runtime replacements could make the tested image differ from the one
> in Gradle's cache key. This restriction includes helper-image overrides such as
> `ryuk.container.image`.

The check covers environment variables, `~/.testcontainers.properties`, and
`testcontainers.properties` in test classpath directories. The Docker Hub prefix
described above remains supported.

For declared test images, choose the replacement directly in the declaration,
for example `image("registry.example/redis:7", "test.redis.image")`. This keeps the
image used by the test consistent with the image in Gradle's cache key.

The plugin does not inspect `testcontainers.properties` inside dependency JARs.
Testcontainers can still load substitutions from those files; they escape this
check and can invalidate cache correctness.
