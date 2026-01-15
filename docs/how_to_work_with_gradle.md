# How to Work with Gradle

This guide covers the fundamentals of working with Gradle in this project. Understanding these concepts will help you navigate the build system and contribute effectively.

## What are Gradle Files?

Gradle builds are defined through a set of build scripts. These scripts can be written in two Domain Specific Languages (DSLs): **Groovy DSL** and **Kotlin DSL**.

### Groovy DSL

The original Gradle DSL uses Groovy syntax. Files use the `.gradle` extension.

```Gradle
plugins {
    id 'java'
}

dependencies {
    implementation 'com.google.guava:guava:32.1.2-jre'
}

tasks.register('hello') {
    doLast {
        println 'Hello from Groovy DSL'
    }
}
```

> [!NOTE]
> Ideally, prefer the Kotlin DSL approach as it has better IDE support. However, due to the
> _script plugins_ this is not always possible in an easy way.

### Kotlin DSL

The Kotlin DSL offers type-safety, better IDE support, and compile-time checking. Files use the `.gradle.kts` extension.

```Gradle Kotlin DSL
plugins {
    id("java")
}

dependencies {
    implementation("com.google.guava:guava:32.1.2-jre")
}

tasks.register("hello") {
    doLast {
        println("Hello from Kotlin DSL")
    }
}
```

Key differences at a glance:

| Aspect              | Groovy DSL               | Kotlin DSL                             |
|---------------------|--------------------------|----------------------------------------|
| File extension      | `.gradle`                | `.gradle.kts`                          |
| String quotes       | Single `'` or double `"` | Double `"` only                        |
| Method calls        | Parentheses optional     | Parentheses required                   |
| Property assignment | `=` optional             | `=` required (mostly)                  |
| IDE support         | Limited                  | Full auto-completion and refactoring   |
| Type safety         | Dynamic typing           | Static typing with compile-time checks |

## Gradle Build Lifecycle

Gradle executes builds in distinct phases. Understanding this lifecycle is essential for writing correct and efficient build logic.

### 1. Initialization Phase

Gradle determines which projects are part of the build. It executes:

- **`init.gradle`** (or scripts in `~/.gradle/init.d/`): Global initialization scripts that run before any project is evaluated
- **`settings.gradle.kts`**: Defines the main repository project structure and discovers subprojects

```Gradle Kotlin DSL
// settings.gradle.kts
rootProject.name = "my-project"

include("module-a")
include("module-b")
include("module-c:submodule")
```

### 2. Configuration Phase

Gradle evaluates all build scripts of the participating projects. During this phase:

- Build scripts (`build.gradle.kts`) are executed
- Tasks are registered and configured
- The task graph is constructed based on dependencies

> [!NOTE]
> Code in the configuration phase runs on **every** build invocation, even if the requested task doesn't need it. Keep configuration-time logic fast and avoid I/O operations.

```Gradle Kotlin DSL
// build.gradle.kts
plugins {
    id("java")
}

// This runs during CONFIGURATION - avoid expensive operations in this phase
val expensiveValue = file("some-file.txt").readText() // Bad!

tasks.register("myTask") {
    // Task configuration also runs during configuration phase
    // But the task ACTION (doLast/doFirst) runs during execution
    doLast {
        // This runs during EXECUTION phase
        println("Executing myTask")
    }
}
```

### 3. Execution Phase

Gradle executes the selected tasks in dependency order. 
Only tasks required to complete the requested goal are executed.

```
./gradlew build

> Task :compileJava
> Task :processResources
> Task :classes
> Task :jar
> Task :assemble
> Task :compileTestJava
> Task :testClasses
> Task :test
> Task :check
> Task :build
```

### Build Logic Location

In a well-organized Gradle project, build logic lives in specific places:

| Location              | Purpose                                                                                            |
|-----------------------|----------------------------------------------------------------------------------------------------|
| `settings.gradle.kts` | Project structure, repository settings, plugin management                                          |
| `build.gradle.kts`    | Project-specific build configuration                                                               |
| `buildSrc/`           | Build logic automatically included by Gradle; contains convention plugins and shared configuration |
| `gradle/`             | Version catalogs and wrapper files and script plugins                                              |

> [!CAUTION]
> Script plugins are not recommended. The best practice for developing our build logic in plugins is 
> to create _convention plugins_ or _binary plugins_.

## Convention Plugins

Convention plugins are the recommended way to share build logic across projects. They encapsulate
common configuration patterns and can be applied like any other plugin.

> [!TIP]
> Convention plugins promote consistency across modules. Instead of copy-pasting configuration,
> define it once and apply it everywhere.

### Project Convention Plugins

Files ending in `.gradle.kts` placed in `buildSrc/src/main/kotlin/` target `Project` and can configure tasks, dependencies, and extensions. The `buildSrc/` directory is automatically included by Gradle before the main build.

In this project, convention plugins use the `dd-trace-java.` prefix. For example, `dd-trace-java.configure-tests.gradle.kts` configures test tasks across all subprojects:

```Gradle Kotlin DSL
// buildSrc/src/main/kotlin/dd-trace-java.configure-tests.gradle.kts (excerpt)

// Use lazy providers to avoid evaluating the property until it is needed
val skipTestsProvider = rootProject.providers.gradleProperty("skipTests")
val skipForkedTestsProvider = rootProject.providers.gradleProperty("skipForkedTests")

// Go through the Test tasks and configure them
tasks.withType<Test>().configureEach {
  // Disable all tests if skipTests property was specified
  onlyIf("skipTests are undefined or false") { !skipTestsProvider.isPresent }

  // Set test timeout for 20 minutes
  timeout.set(Duration.of(20, ChronoUnit.MINUTES))
  
  // ...
}
```

Apply it in any subproject:

```Gradle Kotlin DSL
// dd-java-agent/instrumentation/some-integration/build.gradle.kts
plugins {
    id("dd-trace-java.configure-tests")
}
```

Other convention plugins in this project include:
- `dd-trace-java.gradle-debug` - Debugging utilities for build diagnostics
- `dd-trace-java.dependency-locking` - Dependency locking configuration
- `dd-trace-java.test-jvm-contraints` - JVM constraints for test execution

### Settings Convention Plugins

Files ending in `.settings.gradle.kts` target `Settings` and can configure repository declarations, plugin management, and build structure.

```Gradle Kotlin DSL
// buildSrc/src/main/kotlin/my-settings-convention.settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
```

## Script Plugins

Script plugins are standalone `.gradle` or `.gradle.kts` files that can be applied using the `apply from:` syntax. In this project, they are located in the `gradle/` directory.

```Gradle Kotlin DSL
// Applying a script plugin
apply(from = "$rootDir/gradle/some-script.gradle")
```

> [!WARNING]
> **Script plugins are deprecated.** Gradle 9 documentation no longer mentions them as a recommended practice. They bring several issues:
>
> - **No type safety**: When written in Groovy DSL, you lose IDE support and compile-time checking
> - **Mixed DSL confusion**: Projects often end up with a mix of Groovy and Kotlin scripts
> - **Poor discoverability**: Applied scripts are harder to trace than plugin IDs
> - **No caching**: Script plugins are re-evaluated on every build
>
> **Migrate to convention plugins** in `buildSrc/` or an included build (like `build-logic/`) for better maintainability and performance.

## Gradle Lazy API

Each time Gradle is invoked it project **must** go through the Gradle **Configuration phase**, in which it 
evaluates all build scripts of the participating projects. Any expensive actions in this phase will be run
_every single time_.

This means inefficient configuration directly impacts developer experience by slowing down all builds
— regardless of which tasks actually execute. While time savings per individual task may seem modest,
they compound quickly: **dd-trace-java has ~630 projects and ~33,000 tasks**. At that scale, even small
inefficiencies add up significantly.

The solution is Gradle's **lazy API**: make task creation and configuration as lazy as possible, so Gradle only realizes and configures objects it actually needs to execute.

### Why Lazy Configuration Matters

When you use eager APIs, values are computed immediately during configuration—even if the task never runs. Lazy APIs defer this work to execution time, and Gradle can automatically track dependencies between producers and consumers.

### Eager vs Lazy API Comparison

| Eager (Don't ❌)                 | Lazy (Prefer ✅)                        | Notes                                                                  |
|---------------------------------|----------------------------------------|------------------------------------------------------------------------|
| `configurations.getByName("x")` | `configurations.named("x")`            | Returns a `NamedDomainObjectProvider` instead of resolving immediately |
| `tasks.getByName("x")`          | `tasks.named("x")`                     | Avoids triggering task creation/configuration                          |
| `tasks.findByName("x")`         | `tasks.named("x")`                     | Returns `null` if not found, but still realizes the task               |
| `tasks.findByPath(":x")`        | `tasks.named("x")` on target project   | Realizes task eagerly; use project reference with `named()` instead    |
| `tasks.create("x")`             | `tasks.register("x")`                  | Task is only created when needed                                       |
| `property.set(someValue)`       | `property.set(provider { someValue })` | Defers computation of the value                                        |
| `collection.all { }`            | `collection.configureEach { }`         | Configures lazily as elements are realized                             |
| `collection.forEach { }`        | `collection.configureEach { }`         | Avoids forcing realization of all elements                             |
| `file(path).exists()`           | Use task inputs/outputs                | Let Gradle track file dependencies                                     |
| `exec { }.exitValue`            | See Exec pattern below                 | Avoid running processes at configuration time                          |

> [!IMPORTANT]
> Any function that iterates over a collection (`forEach`, `map`, `filter`, `all`, `any`, `find`, `first`, etc.) will **eagerly realize all elements**. This defeats lazy configuration. Always prefer `configureEach` for configuration, or use `named`/`withType` to get lazy providers.

> [!WARNING]
> **Groovy DSL pitfall**: The shorthand syntax `name { }` is **eager** for both tasks and configurations. It calls `getByName()` under the hood, which realizes the element and its dependencies immediately.
>
> ```Gradle
> // ❌ Eager - realizes the task immediately
> compileLatestDepJava {
>     options.encoding = 'UTF-8'
> }
>
> // ✅ Lazy - configures only when needed
> tasks.named('compileLatestDepJava') {
>     options.encoding = 'UTF-8'
> }
> ```
>
> ```Gradle
> // ❌ Eager - resolves the configuration immediately
> runtimeClasspath {
>     exclude group: 'org.slf4j'
> }
>
> // ✅ Lazy - configures only when needed
> configurations.named('runtimeClasspath') {
>     exclude group: 'org.slf4j'
> }
> ```

### Task Registration

```Gradle Kotlin DSL
// ❌ Eager - task is created immediately
tasks.create("processData") {
    // configuration runs now, even if task is never executed
}

// ✅ Lazy - task is created only when needed
tasks.register("processData") {
    // configuration runs only when this task is in the execution graph
}
```

### Configuration Access

```Gradle Kotlin DSL
// ❌ Eager - resolves configuration immediately
val runtimeClasspath = configurations.getByName("runtimeClasspath")

// ✅ Lazy - returns a provider
val runtimeClasspath = configurations.named("runtimeClasspath")
```

### Walking Collections

```Gradle Kotlin DSL
// ❌ Eager - forces all tasks to be created
tasks.all {
    if (this is JavaCompile) {
        options.encoding = "UTF-8"
    }
}

// ✅ Lazy - configures each task as it's realized
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
```

### Using Providers

```Gradle Kotlin DSL
// ❌ Eager - version is read immediately
tasks.register<Jar>("myJar") {
    archiveVersion.set(project.version.toString())
}

// ✅ Lazy - version is read when the jar task runs
tasks.register<Jar>("myJar") {
    archiveVersion.set(project.provider { project.version.toString() })
}
```

### Lazy JVM Arguments and System Properties

A common pitfall is reading task outputs or other values during configuration. Here's how to pass values lazily to `Test` or `JavaExec` tasks using `CommandLineArgumentProvider`:

```Gradle
// ❌ Eager - task output is resolved at configuration time
tasks.withType(Test).configureEach {
    def fooShadowJarTask = tasks.named('fooShadowJar', ShadowJar)
    def barShadowJarTask = tasks.named('barShadowJarTask', ShadowJar)
    dependsOn fooShadowJarTask, barShadowJarTask
    
    // This resolves the archive path immediately during configuration!
    systemProperty "smoketest.foo.path", fooShadowJarTask.get().archiveFile.get()
    environment "BAR_PATH", barShadowJarTask.get().archiveFile.get()
}

// ✅ Lazy - use CommandLineArgumentProvider to defer resolution
tasks.withType(Test).configureEach {
    def fooShadowJarTask = tasks.named('fooShadowJar', ShadowJar)
    def barShadowJarTask = tasks.named('barShadowJarTask', ShadowJar)
    dependsOn fooShadowJarTask, barShadowJarTask

    jvmArgumentProviders.add(new CommandLineArgumentProvider() {
        @Override
        Iterable<String> asArguments() {
            // This is only called at execution time
            return fooShadowJarTask.map { ["-Dsmoketest.foo.path=${it.archiveFile.get()}"] }.get()
        }
    })
    
    // Workaround: environment() calls toString() at execution time
    environment("BAR_PATH", new Object() {
        @Override
        String toString() {
            return barShadowJarTask.get().archiveFile.get().asFile.absolutePath
        }
    })
}
```

> [!TIP]
> `CommandLineArgumentProvider` is the recommended way to pass lazily-computed JVM arguments. 
> It's configuration-cache compatible and properly tracks inputs for up-to-date checks. However, 
> for older APIs like `environment()` that don't accept providers, use the `toString()` wrapper 
> trick: pass an anonymous object whose `toString()` method computes the value—it will only be 
> called at execution time.

### Benefits of Lazy Configuration

1. **Faster configuration**: Only necessary work is performed
2. **Automatic dependency tracking**: Gradle knows which tasks produce values that others consume
3. **Configuration cache compatibility**: Lazy providers are serializable and can be cached
4. **Correct ordering**: Task dependencies are inferred from provider relationships

## Gradle Daemon JVM

The Gradle Daemon is a long-lived background process that speeds up builds by avoiding JVM startup costs and caching project information. Configuring the Daemon JVM ensures consistent build behavior across machines.

### Daemon JVM Criteria

You can specify criteria for the JVM that runs the Gradle Daemon. This is configured in `gradle/gradle-daemon-jvm.properties`:

```properties
# gradle/gradle-daemon-jvm.properties
toolchainVersion=21
```

When this file exists, Gradle will automatically provision a JVM matching the criteria using toolchain resolvers. This ensures all developers and CI systems use the same JVM version to run the build, regardless of their local `JAVA_HOME`.

> [!NOTE]
> The Daemon JVM is separate from the toolchain used to compile your code. The Daemon JVM runs Gradle itself, while compilation toolchains (configured via `java.toolchain`) compile your source files.

### Updating the Daemon JVM

To change the Daemon JVM version, use the built-in `updateDaemonJvm` task:

```bash
# Update to a specific JVM version
./gradlew updateDaemonJvm --jvm-version=21
```

This task updates the `gradle/gradle-daemon-jvm.properties` file with the new criteria. Commit this file to version control so the entire team uses the same Daemon JVM.

> [!CAUTION]
> Mot using this task will break the JDK auto-provisioning for the Gradle Daemon.


