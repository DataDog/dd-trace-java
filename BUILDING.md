# Building

This documentation provides information for developers to set up their environment and build their project from sources.

* [Development environment](#development-environment)
  * [Quick check](#quick-check)
  * [Requirements](#requirements)
  * [Install JDK](#install-jdk)
  * [Install git](#install-git)
  * [Install Docker Desktop](#install-docker-desktop)
  * [Configure Akka Token](#configure-akka-token)
* [Clone the repository and set up git](#clone-the-repository-and-set-up-git)
* [Building the project](#building-the-project)

## Development environment

### Quick check

To check that your development environment is properly set up to build the project, from the project root run on macOS or Linux:
```shell
./setup.sh
```

or on Windows:
```pwsh
.\setup.ps1
```

Your output should look something like the following:

```
ℹ️ Checking required JVM:
✅ JAVA_HOME is set to /Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home.
ℹ️ Checking other JVMs available for testing:
✅ Azul Zulu JDK 1.8.0_462-b08 from /Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home.
✅ Azul Zulu JDK 11.0.28+6-LTS from /Library/Java/JavaVirtualMachines/zulu-11.jdk/Contents/Home.
✅ Azul Zulu JDK 17.0.16+8-LTS from /Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home.
✅ Azul Zulu JDK 21.0.8+9-LTS from /Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home.
✅ Azul Zulu JDK 25+36-LTS from /Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home.
✅ GraalVM Community JDK 17.0.9+9-jvmci-23.0-b22 from /Library/Java/JavaVirtualMachines/graalvm-ce-java17-22.3.1/Contents/Home.
ℹ️ Checking git configuration:
✅ The git command line is installed.
✅ pre-commit hook is installed in repository.
✅ git config submodule.recurse is set to true.
✅ All git submodules are initialized.
ℹ️ Checking Docker environment:
✅ The docker command line is installed.
✅ The Docker server is running.
```

If there is any issue with your output, check the requirements below and use the following guide to install and configure the required tools.

### Requirements

Requirements to build the full project:

* A recent version (21+) of JDK,
* The `git` command line must be installed,
* A container runtime environment must be available to run all tests (e.g. Docker Desktop).

### Install JDK

Java is required to run Gradle, the project build tool.
Gradle will find any locally installed JDK and can download any missing JDK versions needed for the project build and testing.

#### macOS

Install a recent (21+) JDK using `brew`:
```shell
brew install --cask zulu@21
```

#### Linux

Use your distribution package manager to install a recent (21+) JDK:
```shell
apt install openjdk-21-jdk
```
Alternatively, manually download and install it from [Eclipse Temurin releases](https://adoptium.net/temurin/releases/).

Add the `JAVA_HOME` environment variable to your shell using the `export` command.
You can permanently set it by appending the `export` command to your shell configuration file such as `~/.zshrc`, `~/.bashrc` or similar.
```shell
export JAVA_HOME=/<path to extracted archive>/jdk-21.x.x
```
Restart your shell after applying the changes if you appended the commands to your shell configuration file.

#### Windows

Install a recent (21+) JDK using the Windows package manager `winget`:
```pwsh
winget install --id EclipseAdoptium.Temurin.21.JDK
```
Or manually download and install it from [Eclipse Temurin releases](https://adoptium.net/temurin/releases/).

Set the `JAVA_HOME` environment variable, replacing the path with your JDK 21 installation:
```pwsh
[Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Eclipse Adoptium\jdk-21.x.x-hotspot", [EnvironmentVariableTarget]::User)
```


### Install git

#### macOS

You can trigger the installation by running any `git` command from the terminal, e.g. `git --version`.
If not installed, the terminal will prompt you to install it.

#### Linux

```shell
apt install git
```

#### Windows

Download and install the installer from [the official website](https://git-scm.com/download/win) or install it using the Windows package manager `winget`:

```pwsh
winget install --id git.git
```

### Install Docker Desktop

> [!NOTE]
> Docker Desktop is the recommended container runtime environment, but you can use any other environment to run testcontainers tests.
> Check [the testcontainers container runtime requirements](https://java.testcontainers.org/supported_docker_environment/) for more details.

#### macOS

Download and install Docker Desktop from the official website:<br/>
https://docs.docker.com/desktop/setup/install/mac-install/

#### Linux

Download and install Docker Desktop from the official website:<br/>
https://docs.docker.com/desktop/setup/install/linux/

#### Windows

Download and install Docker Desktop from the official website:<br/>
https://docs.docker.com/desktop/setup/install/windows-install/

<details>
<summary>Alternatively, install Docker Desktop using winget. (click here to expand)</summary>

```pwsh
winget install --id Docker.DockerDesktop
```

</details>

## Clone the repository and set up git

* Get a copy of the project by cloning the repository using git in your workspace:
    ```shell
    git clone --recurse-submodules git@github.com:DataDog/dd-trace-java.git
    ```
* There is a pre-commit hook setup to verify formatting before committing. It can be activated with the following command:
    ```shell
    cd dd-trace-java
    cp .githooks/pre-commit .git/hooks/
    ```

> [!TIP]
> You can alternatively use the `core.hooksPath` configuration to point to the `.githooks` folder using `git config --local core.hooksPath .githooks` if you don't already have a hooks path defined system-wide.

> [!NOTE]
> The git hooks will check that your code is properly formatted before committing.
> This is done both to avoid future merge conflicts and ensure uniformity inside the code base.

* Configure git to automatically update submodules.
  ```shell
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

### Configure Akka Token
> [!NOTE]
> You can skip this step if you don’t need instrumentation for the **akka-http-10.6** module.
> For background on why Akka now requires authentication, see this [article](https://akka.io/blog/why-we-are-changing-the-license-for-akka).

To enable access to Akka artifacts hosted on Lightbend’s private repository, you’ll need to configure an authentication token.
1. Obtain a repository token. Visit the Akka account [page](https://account.akka.io/token) to generate a secure repository token.
2. Set up the environment variable. Create an environment variable named:
```shell
  ORG_GRADLE_PROJECT_akkaRepositoryToken=<your_token>
```

## Building the project

After everything is properly set up, you can build the project or check [the contribution guidelines](CONTRIBUTING.md).

To build the project without running tests, run:
```shell
./gradlew clean assemble
```

To build the entire project with tests (this can take a very long time), run:
```shell
./gradlew clean build
```

>[!NOTE]
> Running the complete test suite on a local development environment can be challenging.
> It may take a very long time, and you might encounter a few flaky tests along the way.
> It is recommended to only run the tests related to your changes locally and leave running the whole test suite to the continuous integration platform.

To build the JVM agent artifact only, run:
```shell
./gradlew :dd-java-agent:shadowJar
```

After building the project, you can find the built JVM agent artifact in the `dd-java-agent/build/libs` folder.
