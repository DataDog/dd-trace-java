# Contributing

Pull requests for bug fixes are welcome, but before submitting new features or changes to current functionality [open an issue](https://github.com/DataDog/dd-trace-java/issues/new)
and discuss your ideas or propose the changes you wish to make. After a resolution is reached a PR can be submitted for review.

When opening a pull request, please open it as a [draft](https://github.blog/2019-02-14-introducing-draft-pull-requests/) to not auto assign reviewers before you feel the pull request is in a reviewable state.

## Requirements

To build the full project:

* JDK version 8 must be installed.
* JDK version 11 must be installed.
* JDK version 17 must be installed.
* `JAVA_8_HOME` must point to the JDK-8 location.
* `JAVA_11_HOME` must point to the JDK-11 location.
* `JAVA_17_HOME` must point to the JDK-17 location.
* The JDK-8 `bin` directory must be the only JDK on the PATH (e.g. `$JAVA_8_HOME/bin`).
* `JAVA_HOME` may be unset. If set, it must point to JDK-8 (same as `JAVA_8_HOME`).

MacOS users, remember that `/usr/libexec/java_home` may control which JDK is in your path.

In contrast to the [IntelliJ IDEA setup](#intellij-idea) the default JVM to build and run tests from the command line should be Java 8.

There is no Oracle JDK v8 for ARM. ARM users might want to use [Azul's Zulu](/Users/albert.cintora/go/src/github.com/DataDog/dd-trace-java/dd-java-agent/instrumentation/build.gradle) builds of Java 8. On MacOS, they can be installed using `brew tap homebrew/cask-versions && brew install --cask zulu8`. [Amazon Corretto](https://aws.amazon.com/corretto/) builds have also been proven to work.

# Building

To build the project without running tests run:
```bash
./gradlew clean assemble
```

To build the entire project with tests (this can take a very long time) run:
```bash
./gradlew clean build
```

# Adding Instrumentations

All instrumentations are in the directory `/dd-java-agent/instrumentation/$framework?/$framework-$minVersion`, where `$framework` is the framework name, and `$minVersion` is the minimum version of the framework supported by the instrumentation.
In some cases, such as [Hibernate](https://github.com/DataDog/dd-trace-java/tree/master/dd-java-agent/instrumentation/hibernate), there is a submodule containing different version-specific instrumentations, but typically a version-specific module is enough when there is only one instrumentation implemented (e.g. [Akka-HTTP](https://github.com/DataDog/dd-trace-java/tree/master/dd-java-agent/instrumentation/akka-http-10.0)).
When adding an instrumentation to `/dd-java-agent/instrumentation/$framework?/$framework-$minVersion`, an include must be added to [`settings.gradle`](https://github.com/DataDog/dd-trace-java/blob/master/settings.gradle):

```groovy
include ':dd-java-agent:instrumentation:$framework?:$framework-$minVersion'
```

Note that the includes are maintained in alphabetical order.

An instrumentation consists of the following components:

* An _Instrumentation_
    * Must implement `Instrumenter` - note that it is recommended to implement `Instrumenter.Default` in every case.
    * The instrumentation must be annotated with `@AutoService(Instrumenter.class)` for annotation processing.
    * The instrumentation must declare a type matcher by implementing the method `typeMatcher()`, which matches the types the instrumentation will transform.
    * The instrumentation must declare every class it needs to load (except for Datadog bootstrap classes, and for the framework itself) in `helperClassNames()`.
      It is recommended to keep the number of classes to a minimum both to reduce deployment size and optimise startup time.
    * If state must be associated with instances of framework types, a definition of the _context store_ by implementing `contextStore()`.
    * The method `transformers()`: this is a map of method matchers to the Bytebuddy advice class which handles matching methods.
      For example, if you want to inject an instance of `com.foo.Foo` into a `com.bar.Bar` (or fall back to a weak map backed association if this is impossible) you would return `singletonMap("com.foo.Foo", "com.bar.Bar")`.
      It may be tempting to write `Foo.class.getName()`, but this will lead to the class being loaded during bootstrapping, which is usually not safe.
      See the section on the [context store](#context-store) for more details.
* A _Decorator_.
  * This will typically extend one of decorator [implementations](https://github.com/DataDog/dd-trace-java/tree/master/dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator), which provide templates for span enrichment behaviour.
  For example, all instrumentations for HTTP server frameworks have decorators which extend [`HttpServerDecorator`](https://github.com/DataDog/dd-trace-java/blob/master/dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/HttpServerDecorator.java).
  * The name of this class must be included in the instrumentation's helper class names, as it will need to be loaded with the instrumentation.
* _Advice_
  * Snippets of code to be inserted at the entry or exit of a method.
  * Associated with the methods they apply to by the instrumentation's `transformers()` method.
* Any more classes required to implement the instrumentation, which must be included in the instrumentation's helper class names.

## Verifying Instrumentations

There are four verification strategies, three of which are mandatory.

### Muzzle directive
A _muzzle directive_ which checks for a range of framework versions that it would be safe to load the instrumentation.
At the top of the instrumentation's gradle file, the following would be added (see [rediscala](https://github.com/DataDog/dd-trace-java/blob/master/dd-java-agent/instrumentation/rediscala-1.8.0/rediscala-1.8.0.gradle))
  ```groovy
        muzzle {
          pass {
            group = "com.github.etaty"
            module = "rediscala_2.11"
            versions = "[1.5.0,)"
            assertInverse = true
          }

          pass {
            group = "com.github.etaty"
            module = "rediscala_2.12"
            versions = "[1.8.0,)"
            assertInverse = true
          }
        }
  ```
This means that the instrumentation should be safe with `rediscala_2.11` from version `1.5.0` and all later versions, but should fail (and so will not be loaded), for older versions (see `assertInverse`).
A similar range of versions is specified for `rediscala_2.12`.
When the agent is built, the muzzle plugin will download versions of the framework and check these directives hold.
To run muzzle on your instrumentation, run:

```groovy
 ./gradlew :dd-java-agent:instrumentation:rediscala-1.8.0:muzzle
```
* ⚠️ Muzzle does _not_ run tests.
  It checks that the types and methods used by the instrumentation are present in particular versions of libraries.
  It can be subverted with `MethodHandle` and reflection, so muzzle passing is not the end of the story.

### Instrumentation Tests

Tests are written in Groovy using the [Spock framework](http://spockframework.org/).
For instrumentations, `AgentTestRunner` must be extended by the test fixture.
For e.g. HTTP server frameworks, there are base tests which enforce consistency between different implementations - see [HttpServerTest](https://github.com/DataDog/dd-trace-java/blob/master/dd-java-agent/testing/src/main/groovy/datadog/trace/agent/test/base/HttpServerTest.groovy)

When writing an instrumentation it is much faster to test just the instrumentation rather than build the entire project, for example:

```bash
./gradlew :dd-java-agent:instrumentation:play-ws-2.1:test
```

### Latest Dependency Tests

Adding a directive to the build file lets us get early warning when breaking changes are released by framework maintainers.
For example, for Play 2.4, based on the following:

```groovy
  latestDepTestCompile group: 'com.typesafe.play', name: 'play-java_2.11', version: '2.5.+'
  latestDepTestCompile group: 'com.typesafe.play', name: 'play-java-ws_2.11', version: '2.5.+'
  latestDepTestCompile(group: 'com.typesafe.play', name: 'play-test_2.11', version: '2.5.+') {
    exclude group: 'org.eclipse.jetty.websocket', module: 'websocket-client'
  }
```

We download the latest dependency and run tests against it.

### Smoke tests

These are tests which run with a real agent jar file set as the `javaagent`.
See [here](https://github.com/DataDog/dd-trace-java/tree/master/dd-smoke-tests).
These are optional and not all frameworks have these, but contributions are very welcome.

# Automatic code formatting

This project includes a `.editorconfig` file for basic editor settings.  This file is supported by most common text editors.

We have automatic code formatting enabled in Gradle configuration using [Spotless](https://github.com/diffplug/spotless)
[Gradle plugin](https://github.com/diffplug/spotless/tree/master/plugin-gradle).
Main goal is to avoid extensive reformatting caused by different IDEs having different opinion about how things should
be formatted by establishing single 'point of truth'.

Running

```bash
./gradlew spotlessApply
```

reformats all the files that need reformatting.

Running

```bash
./gradlew spotlessCheck
```

runs formatting verify task only.

## Pre-commit hook

There is a pre-commit hook setup to verify formatting before committing. It can be activated with this command:

```bash
git config core.hooksPath .githooks
```

## Git submodule setup

Git does not automatically update submodules when switching branches.

Add the following configuration setting or you will need to remember to add `--recurse-submodules` to `git checkout` when switching to old branches.

```bash
git config --local submodule.recurse true
```

This will keep the submodule in `dd-java-agent/agent-jmxfetch/integrations-core` up to date.


## Intellij IDEA

Compiler settings:

* OpenJDK 11 must be installed to build the entire project.  Under `SDKs` it must have the name `11`.
* Under `Build, Execution, Deployment > Compiler > Java Compiler` disable `Use '--release' option for cross-compilation`

Suggested plugins and settings:

* Editor > Code Style > Java/Groovy > Imports
  * Class count to use import with '*': `9999` (some number sufficiently large that is unlikely to matter)
  * Names count to use static import with '*': `9999`
  * With java use the following import layout (groovy should still use the default) to ensure consistency with google-java-format:
    ![import layout](https://user-images.githubusercontent.com/734411/43430811-28442636-94ae-11e8-86f1-f270ddcba023.png)
* [Google Java Format](https://plugins.jetbrains.com/plugin/8527-google-java-format)

## Troubleshooting

* When Gradle is building the project, the error `Could not find netty-transport-native-epoll-4.1.43.Final-linux-x86_64.jar` is shown.
  * Execute `rm -rf  ~/.m2/repository/io/netty/netty-transport*` in a Terminal and re-build again.

* IntelliJ 2021.3 complains `Failed to find KotlinGradleProjectData for GradleSourceSetData` https://youtrack.jetbrains.com/issue/KTIJ-20173
  * Switch to `IntelliJ IDEA CE 2021.2.3`

* IntelliJ Gradle fails to import the project with `JAVA_11_HOME must be set to build Java 11 code`
  * A workaround is to run IntelliJ from terminal with `JAVA_11_HOME`
  * In order to verify what's visible from IntelliJ use `Add Configuration` bar and go to `Add New` -> `Gradle` -> `Environmental Variables`

* Gradle fails with a "too many open files" error.
  * You can check the `ulimit` for open files in your current shell by doing `ulimit -n` and raise it by calling `ulimit -n <new number>`

## Running tests on another JVM

To run tests on a different JVM than the one used for doing the build, you need two things:

1) An environment variable pointing to the JVM to use on the form `JAVA_[JDKNAME]_HOME`, e.g. `JAVA_ZULU15_HOME`, `JAVA_GRAALVM17_HOME`

2) A command line option to the gradle task on the form `-PtestJvm=[JDKNAME]`, e.g. `-PtestJvm=ZULU15`, `-PtestJvm=GRAALVM17`

Please note that the JDK name needs to end with the JDK version, e.g. `11`, `ZULU15`, `ORACLE8`, `GRAALVM17`, etc.

## The APM Test Agent

The APM test agent emulates the APM endpoints of the Datadog Agent. The Test Agent container runs alongside Java tracer
Instrumentation Tests in CI, handling all traces during test runs and performing a number of `Trace Checks`. Trace
Check results are returned within the `Get APM Test Agent Trace Check Results` step for all instrumentation test jobs.

For more information on Trace Checks, see:
https://github.com/DataDog/dd-apm-test-agent#trace-invariant-checks

The APM Test Agent also emits helpful logging, including logging received traces' headers, spans, errors encountered,
ands information on trace checks being performed. Logs can be viewed in CircleCI within the Test-Agent container step
for all instrumentation test suites, ie: `z_test_8_inst` job

Read more about the APM Test Agent:
https://github.com/datadog/dd-apm-test-agent#readme
