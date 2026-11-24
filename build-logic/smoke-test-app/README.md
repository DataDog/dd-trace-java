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

Forwarded JARs use `@Classpath` with the smoke-test project's normalization
rules. If _normalization_ excludes `**/*.version` resources, 
these paths won't be contributing to the fingerprint of added *project jar*.

Because current normalization applied on projects, the generated `.version`
files are ignored, but _base version_ (e.g. `1.69.0-SNAPSHOT`) still need 
to be tracked, as such the project's version is used. For example in 
`projectJar('apiJar', project(':dd-trace-api'))`, the content of `dd-trace-api.version`
is ignored, but `:dd-trace-api`'s version will be used as input.

See the [build-cache guide](../../docs/gradle_build_cache.md#choose-input-fingerprints)
for input annotations, normalization and cache diagnostics.
