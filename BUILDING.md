
# Building

This documentation is dedicated to developers to setup their environment, and build the project from sources:

* Check your development environment is ready to build,
* Check the development environment requirements,
* Guide - How to setup your development environment,
* Guide - How to build the project.

## Development environment quick check

To check that your development environment is properly set up to build the project, you will eventually run `./setup.sh` from the project root and should have its output look something like this:

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
ℹ️ Checking Docker environment:
✅ The docker command line is installed.
✅ The Docker server is running.
```

If the script finds any issue, you can check the requirements and follow the guide below to install and configure the required tools.

## Building requirements

To build the full project:

* The JDK versions 8, 11, 17 and 21 must be installed
* The `JAVA_8_HOME`, `JAVA_11_HOME`, `JAVA_17_HOME`, `JAVA_21_HOME` and `JAVA_GRAALVM17_HOME` must point to their respective JDK location,
* The JDK-8 `bin` directory must be the only JDK on the PATH (e.g. `$JAVA_8_HOME/bin`).
* The `JAVA_HOME` environment variable may be unset. If set, it must point to the JDK 8 location (same as `JAVA_8_HOME`),
* The `git` command line must be installed,
* A container runtime environment must be available to run all tests (usually Docker Desktop). 


## Setting up development environment

### Install the required JDKs

**On MacOS:**

* Install the required JDKs using `brew`:  
`brew install --cask zulu@8 zulu@11 zulu@17 zulu@21 graalvm/tap/graalvm-ce-java17`
* Fix the GraalVM installation by [removing the quarantine flag](https://www.graalvm.org/latest/docs/getting-started/macos/):  
`sudo xattr -r -d com.apple.quarantine /Library/Java/JavaVirtualMachines/graalvm-<current version of graalvm>`
* Add the required environment variables to your shell using the `export` command:
```shell
export JAVA_8_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home  
export JAVA_11_HOME=/Library/Java/JavaVirtualMachines/zulu-11.jdk/Contents/Home
export JAVA_17_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
export JAVA_21_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home
export JAVA_GRAALVM17_HOME=/Library/Java/JavaVirtualMachines/graalvm-<current version of grallvm>/Contents/Home
export JAVA_HOME=$JAVA_8_HOME
```

> [!NOTE] 
> ARM users, there is no Oracle JDK v8 for ARM. 
> It's recommended to use [Azul's Zulu](https://www.azul.com/downloads/?version=java-8-lts&architecture=arm-64-bit&package=jdk#zulu) builds of Java 8.
> [Amazon Corretto](https://aws.amazon.com/corretto/) builds have also been proven to work.

> [!NOTE]
> MacOS users, remember that `/usr/libexec/java_home` may control which JDK is in your path.

> [!NOTE]
> You can permanently install the environment variables by appending the `export` commands into your shell configuration file `~/.zshrc` (or `.bashrc` dependening of your shell).
> You will need to restart your shell first to apply the changes.

**On Linux:**

* Download and extract JDK 8, 11, 17 and 21 from [Eclipse Temurin releases](https://adoptium.net/temurin/releases/) and GraalVM from [Oracle downloads](https://www.graalvm.org/downloads/),
* Install the GraalVM native image requirements for native builds following [the GraalVM official documentation](https://www.graalvm.org/latest/reference-manual/native-image/#prerequisites),
* Add the required environment variables to your shell using the `export` command:
```shell
export JAVA_8_HOME=/<path to extracted archive>/jdk8u<current version of JDK 8>
export JAVA_11_HOME=/<path to extracted archive>/jdk-11.<current version of JDK 11>
export JAVA_17_HOME=/<path to extracted archive>/jdk-17.<current version of JDK 17>
export JAVA_21_HOME=/<path to extracted archive>/jdk-21.<current version of JDK 21>
export JAVA_GRAALVM17_HOME=/<path to extracted archive>/graalvm-jdk-17.<current version of grallvm>/Contents/Home
export JAVA_HOME=$JAVA_8_HOME
```

> [!NOTE]
> You can permanently install the environment variables by appending the `export` commands into your shell configuration file `~/.zshrc` (or `.bashrc` dependening of your shell).
> You will need to restart your shell first to apply the changes.

**On Windows:**

* Download and install JDK 8, 11, 17 and 21 from [Eclipse Temurin releases](https://adoptium.net/temurin/releases/) and GraalVM from [Oracle downloads](https://www.graalvm.org/downloads/),
* Install the GraalVM native image requirements for native builds following [the GraalVM official documentation](https://www.graalvm.org/latest/docs/getting-started/windows/#prerequisites-for-native-image-on-windows),
* Add the required environment variables:
  * Open the *Start Menu*, type `environment variable` and open the *System Properties* using the *Edit environment variable for your account* entry,
  * Add new entries to the table:
    * `JAVA_8_HOME` to the JDK 8 installation folder, usually `C:\Program Files\Eclipse Adoptium\jdk-<current version of Java 8>-hotspot\bin`,
    * `JAVA_11_HOME`, `JAVA_21_HOME`, and `JAVA_21_HOME` similarly to their respective installation `bin` folders,
    * `JAVA_GRAALVM17_HOME` to the GraalVM installation folder, usually `C:\Program Files\Java\<current version of graalvm>\bin`.

### Install git

**On MacOS**, you can trigger the installation running any `git` command from the terminal, like `git --version`.
If not installed, it will prompt you to install it.

**On Linux**, use `# apt-get install git` command.

**On Windows**, [download and install the installer from the offical website](https://git-scm.com/download/win).

### Install Docker Desktop

Download and install Docker Desktop from the offical website for [MacOS](https://docs.docker.com/desktop/install/mac-install/), [Linux](https://docs.docker.com/desktop/install/linux-install/) or [Windows](https://docs.docker.com/desktop/install/windows-install/).

> [!NOTE]
> Docker Desktop is the recommended container runtime environment but you can use any other environment to run testcontainers tests.
> Check [the testcontainers container runtime requirements](https://java.testcontainers.org/supported_docker_environment/) for more details.

### Clone the repository and setup git

First get a copy of the project.
Start a terminal into your workspace and clone the repository using git:
```bash
git clone --recurse-submodules git@github.com:DataDog/dd-trace-java.git
```

Then, install the project git hooks:

There is a pre-commit hook setup to verify formatting before committing. It can be activated with this command:

```bash
# On MacOS and Linux
cd dd-trace-java
cp .githooks/pre-commit .git/hooks/

# On Windows
cd dd-trace-java
copy .githooks/pre-comit .git/hooks/
```

> [!TIP]
> You can alternative use the `core.hooksPath` configuration to point to the `.githooks` folder using `git config --local core.hooksPath .githooks` if you don't already have a hooks path already defined system-wide.

> [!NOTE]
> The git hooks will check your code is properly formatted before commiting it.
> This is done both to avoid future merge conflict and ensure an uniformity inside the code base.

Finally, configure git to automatically update submodules:
```bash
git config --local submodule.recurse true
```

> [!NOTE]
> Git does not automatically update submodules when switching branches.
> Without this configuration, you will need to remember to add `--recurse-submodules` to `git checkout` when switching to old branches.

> [!TIP]
> This will keep the submodule in `dd-java-agent/agent-jmxfetch/integrations-core` up-to-date.
> There is also an automated check when opening a pull request if you are trying to submit an module version change (usually an outdated version).

> [!NOTE]
> Both git configurations (hooks and submodule) will only be applied to the project and won't apply globally to your setup.

### Check your development environment

If you properly followed this guide, the `setup.sh` script at the project root should run without issue, confirming everything is properly setup.

> [!NOTE] 
> The `setup.sh` script is only available for MacOS and Linux.

### Build the project

Now everything is setup, you can then move the next section to start a build, or check [the contribution guidelines](CONTRIBUTING.md).

## Building commands

To build the project without running tests run:
```bash
./gradlew clean assemble
```

To build the entire project with tests (this can take a very long time) run:
```bash
./gradlew clean build
```

After building the project, you can find the jar build artifact into the `dd-java-agent/build/libs` folder.
