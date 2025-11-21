# Bootstrap and Premain Design Guidelines

This document outlines critical design constraints and best practices when developing code that runs during the Java agent's `premain` phase (bootstrap).
Following these guidelines will help avoid breaking applications and ensure maximum compatibility.

## Background

The Java agent runs in two distinct phases:
1. **premain phase**: Code executed before the application's `main` method
2. **post-main phase**: Code executed after the application has started

The premain phase is particularly sensitive because many frameworks and applications configure their runtime environment during or after `main`.
Loading certain Java classes too early can lock in the wrong implementations or cause unexpected behavior.

## Critical Classes to Avoid in Premain

### 1. Java Util Logging (`java.util.logging.*`)

**Why to avoid:**

- Some frameworks set system properties to select different JUL implementations
- Webapp servers may set these properties after `main`
- Using JUL during premain can cause the wrong implementation to become locked-in
- Can cause log-spam and startup delays when the chosen implementation class is not available
- May cause issues when web-apps expect to set up a context class-loader before JUL is used

**What to use instead:**

Logging depends on **when** the code runs, not where it's loaded.

#### Very Early Bootstrap (AgentPreCheck / AgentBootstrap)

Code running before the internal logger is configured ([Agent.java line 154](https://github.com/DataDog/dd-trace-java/blob/v1.53.0/dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/Agent.java#L154)) must use `System.err`, not `System.out`, which is reserved for application output:

```java
System.err.println("Diagnostic message"); // System.err, NOT System.out
```

#### Later Bootstrap Code

After the internal logger is ready, use `org.slf4j` that is shaded and redirects to our internal logger:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

private static final Logger log = LoggerFactory.getLogger(MyClass.class);
```

### 2. Java NIO (`java.nio.*`)

**Why to avoid:**
- Calling `FileSystems.getDefault()` during premain can break applications that set the `java.nio.file.spi.DefaultFileSystemProvider` system property in their `main` method
- Some applications expect to control the default filesystem implementation, and premature initialization locks in the wrong provider
- Loading `java.nio` also triggers native library initialization (`pthread` on Linux) which can introduce a race condition

**Reference:** [Java NIO FileSystems Documentation](https://docs.oracle.com/javase/8/docs/api/java/nio/file/FileSystems.html#getDefault--)

**What to use instead:**
- Use `java.io.*` classes for file operations during premain
- `java.io.File`, `FileInputStream`, `FileOutputStream`, etc. are safe alternatives
- If you must use NIO features, defer initialization until after `main`

**Example from issue #9780:**
```java
// BAD - Triggers java.nio initialization in premain
FileSystems.getDefault();
Path.of("/some/path");

// GOOD - Use java.io instead
new File("/some/path");
new FileInputStream(file);
```

### 3. JMX (`javax.management.*`)

**Why to avoid:**
- Similar to JUL, some frameworks set system properties to select custom JMX builders
- These properties may be set after `main`
- Premature JMX initialization can lock in the wrong builder
- Can cause startup delays if the implementation class is not immediately available

**What to use instead:**
- Defer JMX registration and usage until after the application has started
- Initialize JMX components lazily when first needed

## General Principles

### 1. Minimize Premain Footprint

**Guideline:** Move as much code as possible out of premain

The general direction has been to minimize what runs during premain. Only execute what is absolutely necessary for:
- Setting up the instrumentation framework
- Registering transformers
- Critical initialization that must happen before application code runs

### 2. Avoid Side Effects

**Guideline:** Be extremely careful about what classes are loaded during premain

Loading a class during premain can have unintended side effects:
- Triggers static initializers
- May load related classes
- Can initialize native libraries
- May lock in system property values

### 3. Native Library Initialization

**Guideline:** Be aware of native library loading and initialization

Some Java classes trigger native library loading:
- `java.nio` - triggers pthread initialization on Linux and may create a [race condition (race conditionJDK-8345810)](https://bugs.openjdk.org/browse/JDK-8345810)
- Socket operations - may trigger native networking libraries
- File system operations - platform-specific native code
