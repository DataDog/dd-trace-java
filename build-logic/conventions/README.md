# CI builder image inputs

Apply `dd-trace-java.ci-builder-inputs` to projects whose tests consume executables
or native libraries from the CI builder image:

```groovy
plugins {
  id 'dd-trace-java.ci-builder-inputs'
}
```

The plugin adds `ciBuilderIdentity` to every `Test` task in that project when
`GITLAB_CI=true`. The input contains the image digest, operating system and
architecture. CI must set `CI_BUILDER_IMAGE` to the same immutable
`registry/repository@sha256:...` reference used by the job's `image` setting.

Register other tasks explicitly when they consume the builder environment:

```groovy
ciBuilderInputs.track(tasks.named('quarkusNativeBuild'))
```

A missing or malformed image reference fails when a selected task's inputs are
queried. Commands such as `help` do not require an image reference. The plugin
does not query a registry or invoke external tools.

Local builds receive no additional task inputs. Compiler and Muzzle tasks retain
their existing fingerprints unless explicitly registered with `track`.
