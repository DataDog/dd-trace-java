# Smoke-test application plugin

`dd-trace-java.smoke-test-app` runs nested Gradle or Maven application builds and
exposes their artifacts to the outer project's `Test` tasks.

## Forwarded project JARs

For Gradle applications, add `projectJar` declarations alongside the `gradleApp`
configuration. The third argument selects a named artifact configuration:

```groovy
smokeTestApp {
  projectJar('apiJar', project(':dd-trace-api'))
  projectJar('agentJar', project(':dd-java-agent'), 'shadow')
}
```

Each declaration forwards the resolved JAR path as a Gradle property to the nested
build, such as `-PapiJar=<path>`, and carries its producing task dependency.

## Cache inputs

Forwarded JARs use `@Classpath` input tracking, and will sue the declared normalization
rules of the smoke test project. If normalization excludes `**/*.version` resources, 
these paths won't be contributing to the fingerprint of added *project jar*.

Note however, that the project's version is declared as an input of the
*project jar*. `project.version` supplies a value such as `1.69.0-SNAPSHOT`;
while commit hash is appended when generating version files, which invalidate the 
fingerprint too early.

See the [build-cache guide](../../docs/gradle_build_cache.md#choose-input-fingerprints)
for input annotations, normalization and cache diagnostics.
