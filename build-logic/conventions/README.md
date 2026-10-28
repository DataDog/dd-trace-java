# CI builder image inputs

The `dd-trace-java.ci-builder-inputs` convention tracks the CI builder image identity
in Gradle build cache keys. It is enabled only in GitLab CI (`GITLAB_CI=true`).
It reads the `ciBuilderImage` Gradle property and adds
`ciBuilderIdentity` inputs to `Test`, `Exec` and `JavaExec` tasks in projects that apply it.

> [!NOTE]
> The property is an opaque identity. It can hold the current builder repository
> and version prefix, such as `registry.example/builder:ci-`, or a future content
> hash. Moving a tag to different contents without changing the property does not
> invalidate cached results.

For other tasks, declare the input explicitly. The provider returns an empty map locally:

```kotlin
plugins {
  id("dd-trace-java.ci-builder-inputs")
}

tasks.named("generateNativeLibrary") {
  inputs.property("ciBuilderIdentity", ciBuilderInputs.identity)
}
```
