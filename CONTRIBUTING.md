# Contributing

## Contributions are welcomed

Pull requests for bug fixes are welcome, but before submitting new features or changes to current
functionality, please [open an issue](https://github.com/DataDog/dd-trace-java/issues/new)
and discuss your ideas or propose the changes you wish to make first. After a resolution is reached a PR can be
submitted for
review.

When opening a pull request, please open it as
a [draft](https://github.blog/2019-02-14-introducing-draft-pull-requests/) to not auto assign reviewers before you feel
the pull request is in a reviewable state.

## Adding instrumentations

Check [Adding a New Instrumentation](docs/add_new_instrumentation.md) for instructions on adding a new instrumentation.

Check [How Instrumentations Work](docs/how_instrumentations_work.md) for a deep dive into how instrumentations work.

## Code contributions

### Development environment quick check

Prior to contributing to the project, you can quickly check your development environment using the `./setup.sh` command
line, and fix any issue found using the [Building documentation](BUILDING.md).

### Automatic code formatting

This project includes a `.editorconfig` file for basic editor settings.
This file is supported by most common text editors.

We have automatic code formatting enabled in Gradle configuration using [Spotless](https://github.com/diffplug/spotless)
[Gradle plugin](https://github.com/diffplug/spotless/tree/master/plugin-gradle).
Main goal is to avoid extensive reformatting caused by different IDEs having different opinion about how things should
be formatted by establishing single _point of truth_.

To reformat all the files that need reformatting:

```bash
./gradlew spotlessApply
```

To run formatting verify task only:

```bash
./gradlew spotlessCheck
```

#### IntelliJ IDEA

For IntelliJ IDEA, we suggest the following settings and plugin:

In contrast to the [IntelliJ IDEA set up](CONTRIBUTING.md#intellij-idea) the default JVM to build and run tests from the
command line should be Java 8.

* Use Java 8 to build and run tests:  
  `Project Structure` > `Project` > `SDK` > Use a JDK 1.8
* Configure import formatting:  
  `Editor` > `Code Style` > `Java/Groovy` > `Imports`
    * `Class count to use import with '*'`: `9999` (some number sufficiently large that is unlikely to matter)
    * `Names count to use static import with '*'`: `9999`
    * With java use the following import layout (groovy should still use the default) to ensure consistency with
      google-java-format:
      ![import layout](https://user-images.githubusercontent.com/734411/43430811-28442636-94ae-11e8-86f1-f270ddcba023.png)
* [Google Java Format](https://plugins.jetbrains.com/plugin/8527-google-java-format) plugin

### Troubleshooting

* Gradle fails with a "too many open files" error.
    * You can check the `ulimit` for open files in your current shell by doing `ulimit -n` and raise it by
      calling `ulimit -n <new number>`

<details>
  <summary>More past issues</summary>

* When Gradle is building the project, the
  error `Could not find netty-transport-native-epoll-4.1.43.Final-linux-x86_64.jar` is shown.
    * Execute `rm -rf  ~/.m2/repository/io/netty/netty-transport*` in a Terminal and re-build again.

* IntelliJ 2021.3
  complains `Failed to find KotlinGradleProjectData for GradleSourceSetData` https://youtrack.jetbrains.com/issue/KTIJ-20173
    * Switch to `IntelliJ IDEA CE 2021.2.3`

* IntelliJ Gradle fails to import the project with `JAVA_11_HOME must be set to build Java 11 code`
    * A workaround is to run IntelliJ from terminal with `JAVA_11_HOME`
    * In order to verify what's visible from IntelliJ use `Add Configuration` bar and go
      to `Add New` -> `Gradle` -> `Environmental Variables`

</details>
