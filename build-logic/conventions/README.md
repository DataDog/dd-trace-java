# CI builder image inputs

Apply `dd-trace-java.ci-builder-inputs` to projects whose tests consume executables
or native libraries from the CI builder image:

```groovy
plugins {
  id 'dd-trace-java.ci-builder-inputs'
}
```

The plugin adds `ciBuilderIdentity` to every `Test` task in that project when
`GITLAB_CI=true`. The input contains the `ciBuilderImage` Gradle property,
operating system and architecture. CI can supply the property through
`ORG_GRADLE_PROJECT_ciBuilderImage`; `-PciBuilderImage=...` overrides that value.

The property is an opaque identity. It can hold the current builder repository
and version prefix, such as `registry.example/builder:ci-`, or a future content
hash. Moving a tag to different contents without changing the property does not
invalidate cached results. Supply a content hash to detect those changes.

Register other tasks explicitly when they consume the builder environment:

```groovy
ciBuilderInputs.track(tasks.named('quarkusNativeBuild'))
```

A missing or blank identity fails when a selected task's inputs are queried.
Commands such as `help` do not require an identity. The plugin
does not query a registry or invoke external tools.

Local builds receive no additional task inputs. Compiler and Muzzle tasks retain
their existing fingerprints unless explicitly registered with `track`.
