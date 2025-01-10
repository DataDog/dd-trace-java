# Building

This documentation provides information for developers to set up their environment and build their project from sources.

* [Development environment quick check](#development-environment-quick-check)
* [Environment requirements quick check](#environment-requirements-quick-check)
* [Development environment set up](#development-environment-set-up)
* [Project build](#project-build)

## Development environment quick check

To check that your development environment is properly set up to build the project, run `./setup.sh` on macOS or Linux (or `.\setup.ps1` on Windows) from the project root. Your output should look something like the following:

```
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
✅ All git submodules are initialized.
ℹ️ Checking Docker environment:
✅ The docker command line is installed.
✅ The Docker server is running.
```

If there is any issue with your output, you can check the requirements and/or follow the guide below to install and configure the required tools.

## Environment requirements quick check

Requirements to build the full project:

* The JDK versions 8, 11, 17 and 21 must be installed.
* The `JAVA_8_HOME`, `JAVA_11_HOME`, `JAVA_17_HOME`, `JAVA_21_HOME` and `JAVA_GRAALVM17_HOME` must point to their respective JDK location.
* The JDK-8 `bin` directory must be the only JDK on the PATH (e.g. `$JAVA_8_HOME/bin`).
* The `JAVA_HOME` environment variable may be unset. If set, it must point to the JDK 8 location (same as `JAVA_8_HOME`).
* The `git` command line must be installed.
* A container runtime environment must be available to run all tests (e.g. Docker Desktop).

## Development environment set up

### Install the required JDKs

Download and install Eclipse Temurin JDK versions 8, 11, 17 and 21, and GraalVM.

<details>
<summary>macOS</summary>

* Install the required JDKs using `brew`.
`brew install --cask zulu@8 zulu@11 zulu@17 zulu@21 graalvm/tap/graalvm-ce-java17`
* Fix the GraalVM installation by [removing the quarantine flag](https://www.graalvm.org/latest/docs/getting-started/macos/).
`sudo xattr -r -d com.apple.quarantine /Library/Java/JavaVirtualMachines/graalvm-<current version of graalvm>`
* Add the required environment variables to your shell using the `export` command. You can permanently install the environment variables by appending the `export` commands into your shell configuration file `~/.zshrc` or `.bashrc` or other.
```shell
export JAVA_8_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
export JAVA_11_HOME=/Library/Java/JavaVirtualMachines/zulu-11.jdk/Contents/Home
export JAVA_17_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
export JAVA_21_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home
export JAVA_GRAALVM17_HOME=/Library/Java/JavaVirtualMachines/graalvm-<current version of graalvm>/Contents/Home
export JAVA_HOME=$JAVA_8_HOME
```
* Restart your shell after applying the changes if you appended the commands to your shell configuration file.

> [!NOTE]
> ARM users: there is no Oracle JDK v8 for ARM.
> It's recommended to use [Azul's Zulu](https://www.azul.com/downloads/?version=java-8-lts&architecture=arm-64-bit&package=jdk#zulu) builds of Java 8.
> [Amazon Corretto](https://aws.amazon.com/corretto/) builds have also been proven to work.

> [!NOTE]
> MacOS users: remember that `/usr/libexec/java_home` may control which JDK is in your path.

</details>

<details>
<summary>Linux</summary>

* Download and extract JDK 8, 11, 17, and 21 from [Eclipse Temurin releases](https://adoptium.net/temurin/releases/) and GraalVM from [Oracle downloads](https://www.graalvm.org/downloads/).
* Install the GraalVM native image requirements for native builds by following [the GraalVM official documentation](https://www.graalvm.org/latest/reference-manual/native-image/#prerequisites).
* Add the required environment variables to your shell using the `export` command. You can permanently install the environment variables by appending the `export` commands into your shell configuration file `~/.zshrc` or `~/.bashrc` or other.
```shell
export JAVA_8_HOME=/<path to extracted archive>/jdk8u<current version of JDK 8>
export JAVA_11_HOME=/<path to extracted archive>/jdk-11.<current version of JDK 11>
export JAVA_17_HOME=/<path to extracted archive>/jdk-17.<current version of JDK 17>
export JAVA_21_HOME=/<path to extracted archive>/jdk-21.<current version of JDK 21>
export JAVA_GRAALVM17_HOME=/<path to extracted archive>/graalvm-jdk-17.<current version of graalvm>/Contents/Home
export JAVA_HOME=$JAVA_8_HOME
```
* Restart your shell after applying the changes if you appended the commands to your shell configuration file.

</details>

<details>
<summary>Windows</summary>

Use the `install-jdks-windows.ps1` script to download and install Eclipse Temurin JDK versions 8, 11, 17, and 21, and set the required environment variables.

To install the JDKs manually using `winget`, use the following commands. After the JDKs are installed, you can still use `install-jdks-windows.ps1` to add the environment variables.

```
winget install --id EclipseAdoptium.Temurin.8.JDK
winget install --id EclipseAdoptium.Temurin.11.JDK
winget install --id EclipseAdoptium.Temurin.17.JDK
winget install --id EclipseAdoptium.Temurin.21.JDK
```

To install the JDKs manually:
* Download SDKs manually from [Eclipse Temurin releases](https://adoptium.net/temurin/releases/) and GraalVM from [Oracle downloads](https://www.graalvm.org/downloads/).
* Install the GraalVM native image requirements for native builds by following [the GraalVM official documentation](https://www.graalvm.org/latest/docs/getting-started/windows/#prerequisites-for-native-image-on-windows).

To add the required environment variables manually from PowerShell, run this command for each SDK version:
```pwsh
[Environment]::SetEnvironmentVariable("JAVA_8_HOME", "C:\Program Files\Eclipse Adoptium\jdk-<version>-hotspot", [EnvironmentVariableTarget]::User)
```

To add the required environment variables manually using the UI:
* Open the *Start Menu*, type `environment variable`, and use the *Edit environment variable for your account* entry to open the *System Properties*.
* Add new entries to the table:
  * `JAVA_8_HOME` to the JDK 8 installation folder, usually `C:\Program Files\Eclipse Adoptium\jdk-<current version of Java 8>-hotspot\bin`
  * `JAVA_11_HOME`, `JAVA_17_HOME`, and `JAVA_21_HOME` similarly to their respective installation `bin` folders
  * `JAVA_GRAALVM17_HOME` to the GraalVM installation folder, usually `C:\Program Files\Java\<current version of graalvm>\bin`

</details>

### Install `git`

**On macOS:**

You can trigger the installation by running any `git` command from the terminal, e.g. `git --version`.
If not installed, the terminal will prompt you to install it.

**On Linux:**

Run `apt-get install git`.

**On Windows:**

Run `winget install --id git.git`. Alternatively, download and install the installer from [the official website](https://git-scm.com/download/win).

### Install Docker Desktop

> [!NOTE]
> Docker Desktop is the recommended container runtime environment, but you can use any other environment to run testcontainers tests.
> Check [the testcontainers container runtime requirements](https://java.testcontainers.org/supported_docker_environment/) for more details.

**On macOS:**

Download and install Docker Desktop from the offical website: https://docs.docker.com/desktop/setup/install/mac-install/

**On Linux:**

Download and install Docker Desktop from the offical website: https://docs.docker.com/desktop/setup/install/linux/

**On Windows:**

Run `winget install --id Docker.DockerDesktop`.

Alternatively, download and install Docker Desktop from the offical website: https://docs.docker.com/desktop/setup/install/windows-install/

### Clone the repository and set up git

* Get a copy of the project by cloning the repository using git in your workspace:
    ```bash
    git clone --recurse-submodules git@github.com:DataDog/dd-trace-java.git
    ```
* There is a pre-commit hook setup to verify formatting before committing. It can be activated with the following command:
    ```bash
    # On bash-like shells shells or PowerShell
    cd dd-trace-java
    cp .githooks/pre-commit .git/hooks/
    ```

  > [!TIP]
  > You can alternatively use the `core.hooksPath` configuration to point to the `.githooks` folder using `git config --local core.hooksPath .githooks` if you don't already have a hooks path defined system-wide.

  > [!NOTE]
  > The git hooks will check that your code is properly formatted before commiting.
  > This is done both to avoid future merge conflict and ensure uniformity inside the code base.

* Configure git to automatically update submodules.
  ```bash
  git config --local submodule.recurse true
  ```

  > [!NOTE]
  > Git does not automatically update submodules when switching branches.
  > Without this configuration, you will need to remember to add `--recurse-submodules` to `git checkout` when switching to old branches.

  > [!TIP]
  > This will keep the submodule in `dd-java-agent/agent-jmxfetch/integrations-core` up-to-date.
  > There is also an automated check when opening a pull request if you are trying to submit a module version change (usually an outdated version).

> [!NOTE]
> Both git configurations (hooks and submodule) will only be applied to this project and won't apply globally in your setup.

### Check your development environment

You can confirm that your development environment is properly set up using the [quick check](#development-environment-quick-check) `setup.sh` script on macOS or Linux or `setup.ps1` on Windows.

### Build the project

After everything is properly set up, you can move on to the next section to start a build or check [the contribution guidelines](CONTRIBUTING.md).

## Project build

To build the project without running tests, run:
```bash
./gradlew clean assemble
```

To build the entire project with tests (this can take a very long time), run:
```bash
./gradlew clean build
```

>[!NOTE]
> Running the complete test suite on a local development environment can be challenging.
> It may take a very long time, and you might encounter a few flaky tests along the way.
> It is recommended to only run the tests related to your changes locally and leave running the whole test suite to the continuous integration platform.

To build the JVM agent artifact only, run:
```bash
./gradlew :dd-java-agent:shadowJar
```

After building the project, you can find the built JVM agent artifact in the `dd-java-agent/build/libs` folder.
