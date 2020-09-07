# Contributing

Pull requests for bug fixes are welcome, but before submitting new features or changes to current functionality [open an issue](https://github.com/DataDog/dd-trace-java/issues/new)
and discuss your ideas or propose the changes you wish to make. After a resolution is reached a PR can be submitted for review.

## Requirements

To build the full project from the command line you need to have JDK versions for 7,8,11, and 14 installed on your machine, as well as the following environment variables set up: `JAVA_7_HOME, JAVA_8_HOME, JAVA_11_HOME, JAVA_14_HOME`, pointing to the respective JDK.

In contrast to the [IntelliJ IDEA setup](#intellij-idea) the default JVM to build and run tests from the command line should be Java 8.

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

## Intellij IDEA

Compiler settings:

* OpenJDK 11 must be installed to build the entire project.  Under `SDKs` it must have the name `11`.
* Under `Build, Execution, Deployment > Compiler > Java Compiler` disable `Use '--release' option for cross-compilation`

Required plugins:

* [Lombok](https://plugins.jetbrains.com/plugin/6317-lombok-plugin)

Suggested plugins and settings:

* Editor > Code Style > Java/Groovy > Imports
  * Class count to use import with '*': `9999` (some number sufficiently large that is unlikely to matter)
  * Names count to use static import with '*': `9999`
  * With java use the following import layout (groovy should still use the default) to ensure consistency with google-java-format:
    ![import layout](https://user-images.githubusercontent.com/734411/43430811-28442636-94ae-11e8-86f1-f270ddcba023.png)
* [Google Java Format](https://plugins.jetbrains.com/plugin/8527-google-java-format)
* [Save Actions](https://plugins.jetbrains.com/plugin/7642-save-actions)
  ![Recommended Settings](https://user-images.githubusercontent.com/734411/43430944-db84bf8a-94ae-11e8-8cec-0daa064937c4.png)

## Troubleshooting

P: When Gradle is building the project, the error `Could not find netty-transport-native-epoll-4.1.43.Final-linux-x86_64.jar` is shown.
S: Execute `rm -rf  ~/.m2/repository/io/netty/netty-transport*` in a Terminal and re-build again.
