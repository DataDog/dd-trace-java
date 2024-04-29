
# Building

## Development environment quick check

You can quickly check that your development environment is properly set up to build the project running `./setup.sh` from the project root:

```bash
$ ./setup.sh
ℹ️ Checking required JVM:
✅ JAVA_HOME is set to /Users/datadog/.sdkman/candidates/java/8.0.402-zulu.
✅ JAVA_8_HOME is set to /Users/datadog/.sdkman/candidates/java/8.0.402-zulu.
✅ JAVA_11_HOME is set to /Users/datadog/.sdkman/candidates/java/11.0.22-zulu.
✅ JAVA_17_HOME is set to /Users/datadog/.sdkman/candidates/java/17.0.10-zulu.
✅ JAVA_21_HOME is set to /Users/datadog/.sdkman/candidates/java/21.0.2-zulu.
✅ JAVA_GRAALVM17_HOME is set to /Users/datadog/.sdkman/candidates/java/17.0.9-graalce.
ℹ️ Checking git configuration:
✅ The git command line is installed.
✅ pre-commit hook is installed in repository.
✅ git config submodule.recurse is set to true.
ℹ️ Checking shell configuration:
✅ File descriptor limit is set to 12800.
✅ The docker command line is installed.
✅ The Docker server is running.
```

If the script finds any issue, you can follow the requirements below to install and configure the required tools and [the code contribution guidelines](CONTRIBUTING.md#code-contributions).

## Building requirements

To build the full project:

* JDK version 8 must be installed.
* JDK version 11 must be installed.
* JDK version 17 must be installed.
* JDK version 21 must be installed.
* `JAVA_8_HOME` must point to the JDK-8 location.
* `JAVA_11_HOME` must point to the JDK-11 location.
* `JAVA_17_HOME` must point to the JDK-17 location.
* `JAVA_21_HOME` must point to the JDK-21 location.
* The JDK-8 `bin` directory must be the only JDK on the PATH (e.g. `$JAVA_8_HOME/bin`).
* `JAVA_HOME` may be unset. If set, it must point to JDK-8 (same as `JAVA_8_HOME`).
* git command line must be installed.
* A container runtime environment must be available to run all tests (usually Docker Desktop). 

> [!NOTE]
> MacOS users, remember that `/usr/libexec/java_home` may control which JDK is in your path.

> [!NOTE] 
> ARM users, there is no Oracle JDK v8 for ARM. 
> You might want to use [Azul's Zulu](https://www.azul.com/downloads/?version=java-8-lts&architecture=arm-64-bit&package=jdk#zulu) builds of Java 8.
> On macOS, they can be installed using `brew tap homebrew/cask-versions && brew install --cask zulu8`.
> [Amazon Corretto](https://aws.amazon.com/corretto/) builds have also been proven to work.

## Building commands

To build the project without running tests run:

```bash
./gradlew clean assemble
```

To build the entire project with tests (this can take a very long time) run:

```bash
./gradlew clean build
```
