# How Instrumentations Work

## Introduction

Around 120 integrations consisting of about 200 instrumentations are currently provided with the Datadog Java Trace
Agent.
An auto-instrumentation allows compiled Java applications to be instrumented at runtime by a Java agent.
This happens when compiled classes matching rules defined in the instrumentation undergo bytecode manipulation to
accomplish some of what could be done by a developer instrumenting the code manually.
Instrumentations are maintained in `/dd-java-agent/instrumentation/`

## Files/Directories

Instrumentations are in the directory:

`/dd-java-agent/instrumentation/$framework/$framework-$minVersion`

where `$framework` is the framework name, and `$minVersion` is the minimum version of the framework supported by the
instrumentation.
For example:

```
$ tree dd-java-agent/instrumentation/couchbase -L 2
dd-java-agent/instrumentation/couchbase
├── couchbase-2.0
│   ├── build.gradle
│   └── src
├── couchbase-2.6
│   ├── build.gradle
│   └── src
├── couchbase-3.1
│   ├── build.gradle
│   └── src
└── couchbase-3.2
    ├── build.gradle
    └── src
```

In some cases, such as [Hibernate](../dd-java-agent/instrumentation/hibernate), there is a submodule containing
different version-specific instrumentations, but typically a version-specific module is enough when there is only one
instrumentation implemented (e.g. [Akka-HTTP](../dd-java-agent/instrumentation/akka/akka-http/akka-http-10.0))

## Gradle

Instrumentations included when building the Datadog java trace agent are defined in
[`/settings.gradle`](../settings.gradle.kts) in alphabetical order with the other instrumentations in this format:

```kotlin
include(":dd-java-agent:instrumentation:<framework>:<framework>-<minVersion>")
```

Dependencies specific to a particular instrumentation are added to the `build.gradle` file in that instrumentation’s
directory.
Declare necessary dependencies under `compileOnly` configuration so they do not leak into the agent jar.

## Muzzle

Muzzle directives are applied at build time from the `build.gradle` file.
OpenTelemetry provides some [Muzzle documentation](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/docs/contributing/muzzle.md).
Muzzle directives check for a range of framework versions that are safe to load the instrumentation.

See this excerpt as an example from [rediscala](../dd-java-agent/instrumentation/rediscala-1.8/build.gradle):

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

This means that the instrumentation should be safe with `rediscala_2.11` from version `1.5.0` and all later versions,
but should fail (and so will not be loaded), for older versions (see `assertInverse`).
A similar range of versions is specified for `rediscala_2.12`.
When the agent is built, the muzzle plugin will download versions of the framework and check these directives hold.
To run muzzle on your instrumentation, run:

```shell
./gradlew :dd-java-agent:instrumentation:rediscala-1.8:muzzle
```

> [!WARNING]
> Muzzle does _not_ run tests.
> It checks that the types and methods used by the instrumentation are present in particular versions of libraries.
> It can be subverted with `MethodHandle` and reflection -- in other words, having the `muzzle` task passing is not enough
> to validate an instrumentation.

By default, all the muzzle directives are checked against all the instrumentations included in a module.
However, there can be situations in which it's only needed to check one specific directive on an instrumentation.
At this point the instrumentation should override the method `muzzleDirective()` by returning the name of the directive to execute.

### Identifying Breaking Changes with JApiCmp

Before defining muzzle version ranges, you can use the JApiCmp plugin to compare different versions of a library and
identify breaking API changes. This helps determine where to split version ranges in your muzzle directives.

The `japicmp` task compares two versions of a Maven artifact and reports:
- Removed classes and methods (breaking changes)
- Added classes and methods (non-breaking changes)
- Modified methods with binary compatibility status

#### Usage

Compare two versions of any Maven artifact:

```shell
./gradlew japicmp -Partifact=groupId:artifactId -Pbaseline=oldVersion -Ptarget=newVersion
```

For example, to compare MongoDB driver versions:

```shell
./gradlew japicmp -Partifact=org.mongodb:mongodb-driver-sync -Pbaseline=3.11.0 -Ptarget=4.0.0
```

#### Output

The task generates two reports:

- **Text report**: `build/reports/japicmp.txt` - Detailed line-by-line comparison
- **HTML report**: `build/reports/japicmp.html` - Browsable visual report

## Instrumentation classes

The Instrumentation class is where the instrumentation begins. It will:

1. Use Matchers to choose target types (i.e., classes)
2. From only those target types, use Matchers to select the members (i.e., methods) to instrument.
3. Apply instrumentation code from an Advice class to those members.

Instrumentation classes:

1. Must be annotated with `@AutoService(InstrumenterModule.class)`
2. Should be declared in a file that ends with `Instrumentation.java`
3. Should extend one of the six abstract TargetSystem `InstrumenterModule` classes
4. Should implement one of the `Instrumenter` interfaces

For example:

```java
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

@AutoService(InstrumenterModule.class)
public class RabbitChannelInstrumentation extends InstrumenterModule.Tracing
        implements Instrumenter.ForTypeHierarchy {/* */
}
```

|                                                                                                                                                                                                                                  |                                                                                                            |
|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------|
| **TargetSystem**                                                                                                                                                                                                                 | **Usage**                                                                                                  |
| `InstrumenterModule.`[`Tracing`](https://github.com/DataDog/dd-trace-java/blob/82a3400cd210f4051b92fe1a86cd1b64a17e005e/dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/InstrumenterModule.java#L184)      | An Instrumentation class should extend an appropriate provided TargetSystem class when possible.           |
| `InstrumenterModule.`[`Profiling`](https://github.com/DataDog/dd-trace-java/blob/82a3400cd210f4051b92fe1a86cd1b64a17e005e/dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/InstrumenterModule.java#L196)    |                                                                                                            |
| `InstrumenterModule.`[`AppSec`](https://github.com/DataDog/dd-trace-java/blob/82a3400cd210f4051b92fe1a86cd1b64a17e005e/dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/InstrumenterModule.java#L215)       |                                                                                                            |
| `InstrumenterModule.`[`Iast`](https://github.com/DataDog/dd-trace-java/blob/82a3400cd210f4051b92fe1a86cd1b64a17e005e/dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/InstrumenterModule.java#L228)         |                                                                                                            |
| `InstrumenterModule.`[`CiVisibility`](https://github.com/DataDog/dd-trace-java/blob/82a3400cd210f4051b92fe1a86cd1b64a17e005e/dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/InstrumenterModule.java#L285) |                                                                                                            |
| `InstrumenterModule.`[`Usm`](https://github.com/DataDog/dd-trace-java/blob/82a3400cd210f4051b92fe1a86cd1b64a17e005e/dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/InstrumenterModule.java#L273)          |                                                                                                            |
| `InstrumenterModule.`[`ContextTracking`]()                                                                                                                                                                                       | For instrumentations that only track context propagation without creating tracing spans.                   |
| [`InstrumenterModule`](https://github.com/DataDog/dd-trace-java/blob/82a3400cd210f4051b92fe1a86cd1b64a17e005e/dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/InstrumenterModule.java)                     | Avoid extending `InstrumenterModule` directly.  When no other TargetGroup is applicable we generally default to `InstrumenterModule.Tracing` |

### Grouping Instrumentations

Related instrumentations may be grouped under a single `InstrumenterModule` to share common details such as integration
name, helpers, context store use, and optional `classLoaderMatcher()`.

Module classes:

1. Must be annotated with `@AutoService(InstrumenterModule.class)`
2. Should be declared in a file that ends with `Module.java`
3. Should extend one of the six abstract TargetSystem `InstrumenterModule` classes
4. Should have a `typeInstrumentations()` method that returns the instrumentations in the group
5. Should NOT implement one of the `Instrumenter` interfaces

> [!WARNING]
> Grouped instrumentations must NOT be annotated with `@AutoService(InstrumenterModule.class)
> and must NOT extend any of the six abstract `TargetSystem` `InstrumenterModule` classes.

Existing instrumentations can be grouped under a new module, assuming they share the same integration name.

For each member instrumentation:

1. Remove `@AutoService(InstrumenterModule.class)`
2. Remove `extends InstrumenterModule...`
3. Move the list of helpers to the module, merging as necessary
4. Move the context store map to the module, merging as necessary

### Type Matching

Instrumentation classes should implement an
appropriate [Instrumenter interface](../dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/Instrumenter.java)
that specifies how target types will be selected for instrumentation.

|                                                                                                                                                                                                                     |                                                                                    |                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Instrumenter Interface**                                                                                                                                                                                          | **Method(s)**                                                                      | **Usage(Example)**                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| [`ForSingleType`](https://github.com/DataDog/dd-trace-java/blob/297b575f0f265c1dc78f9958e7b4b9365c80d1f9/dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/Instrumenter.java#L70)               | `String instrumentedType()`                                                        | Instruments only a single class name known at compile time.(see [Json2FactoryInstrumentation](https://github.com/DataDog/dd-trace-java/blob/9a28dc3f0333e781b2defc378c9020bf0a44ee9a/dd-java-agent/instrumentation/jackson-core/src/main/java/datadog/trace/instrumentation/jackson/core/Json2FactoryInstrumentation.java#L19))                                                                                                                                                                                                                                                                                                                                                                                 |
| [`ForKnownTypes`](https://github.com/DataDog/dd-trace-java/blob/297b575f0f265c1dc78f9958e7b4b9365c80d1f9/dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/Instrumenter.java#L75)               | `String[] knownMatchingTypes()`                                                    | Instruments multiple class names known at compile time.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| [`ForTypeHierarchy`](https://github.com/DataDog/dd-trace-java/blob/297b575f0f265c1dc78f9958e7b4b9365c80d1f9/dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/Instrumenter.java#L80)            | `String hierarchyMarkerType()``ElementMatcher<TypeDescription> hierarchyMatcher()` | Composes more complex matchers using chained [HierarchyMatchers](https://github.com/DataDog/dd-trace-java/blob/9a28dc3f0333e781b2defc378c9020bf0a44ee9a/dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/bytebuddy/matcher/HierarchyMatchers.java#L18) methods.  The `hierarchyMarkerType()` method should return a type name.  Classloaders without this type can skip the more expensive `hierarchyMatcher()` method. (see [HttpClientInstrumentation](https://github.com/DataDog/dd-trace-java/blob/9a28dc3f0333e781b2defc378c9020bf0a44ee9a/dd-java-agent/instrumentation/java-http-client/src/main/java/datadog/trace/instrumentation/httpclient/HttpClientInstrumentation.java#L43)) |
| [`ForConfiguredType`](https://github.com/DataDog/dd-trace-java/blob/297b575f0f265c1dc78f9958e7b4b9365c80d1f9/dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/Instrumenter.java#L93) | `Collection<String> configuredMatchingTypes()`                                     | **_Do not implement this interface_**_._Use `ForKnownType` instead.  `ForConfiguredType` is only used   for last minute additions in the field - such as when a customer has a new JDBC driver that's not in the allowed list and we need to  test it and provide a workaround until the next release.                                                                                                                                                                                                                                                                                                                                                                                                          |
| [`ForConfiguredTypes`](https://github.com/DataDog/dd-trace-java/blob/297b575f0f265c1dc78f9958e7b4b9365c80d1f9/dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/Instrumenter.java#L88)          | `String configuredMatchingType();`                                                 | **_Do not implement this interface._** __Like `ForConfiguredType,` for multiple classes                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |

When matching your instrumentation against target types,
prefer [ForSingleType](https://github.com/DataDog/dd-trace-java/blob/5cab82068b689a46970d9132a142a364548a82fa/dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/Instrumenter.java#L68)
or [ForKnownTypes](https://github.com/DataDog/dd-trace-java/blob/5cab82068b689a46970d9132a142a364548a82fa/dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/Instrumenter.java#L73)
over more
expensive [ForTypeHierarchy](https://github.com/DataDog/dd-trace-java/blob/5cab82068b689a46970d9132a142a364548a82fa/dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/Instrumenter.java#L78)
matching.

Consider adding an
appropriate [ClassLoaderMatcher](https://github.com/DataDog/dd-trace-java/blob/82a3400cd210f4051b92fe1a86cd1b64a17e005e/dd-java-agent/agent-tooling/src/main/java/datadog/trace/agent/tooling/InstrumenterModule.java#L137)
so the Instrumentation only activates when that class is loaded. For example:

```java
@Override
public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return hasClassNamed("java.net.http.HttpClient");
}
```

The `Instrumenter.ForBootstrap` interface is a hint that this instrumenter works on bootstrap types and there is no
classloader present to interrogate. Use it when instrumenting something from the JDK that will be on the bootstrap
classpath. For
example, [`ShutdownInstrumentation`](https://github.com/DataDog/dd-trace-java/blob/3e81c006b54f73aae61f88c39b52a7267267075b/dd-java-agent/instrumentation/shutdown/src/main/java/datadog/trace/instrumentation/shutdown/ShutdownInstrumentation.java#L18)
or [`UrlInstrumentation`](https://github.com/DataDog/dd-trace-java/blob/3e81c006b54f73aae61f88c39b52a7267267075b/dd-java-agent/instrumentation/http-url-connection/src/main/java/datadog/trace/instrumentation/http_url_connection/UrlInstrumentation.java#L21).

> [!NOTE]
> Without classloader available, helper classes for bootstrap instrumentation must be place into the 
> `:dd-java-agent:agent-bootstrap` module rather than loaded using [the default mechanism](#helper-classes). 

### Method Matching

After the type is selected, the type’s target members(e.g., methods) must next be selected using the Instrumentation
class’s `adviceTransformations()` method.
ByteBuddy’s [`ElementMatchers`](https://javadoc.io/doc/net.bytebuddy/byte-buddy/1.4.17/net/bytebuddy/matcher/ElementMatchers.html)
are used to describe the target members to be instrumented.
Datadog’s [`DDElementMatchers`](../dd-java-agent/agent-builder/src/main/java/datadog/trace/agent/tooling/bytebuddy/matcher/DDElementMatchers.java)
class also provides these 10 additional matchers:

* implementsInterface
* hasInterface
* hasSuperType
* declaresMethod
* extendsClass
* concreteClass
* declaresField
* declaresContextField
* declaresAnnotation
* hasSuperMethod

Here, any public `execute()` method taking no arguments will have `PreparedStatementAdvice` applied:

```java
@Override
public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
            nameStartsWith("execute")
                    .and(takesArguments(0))
                    .and(isPublic()),
            getClass().getName() + "$PreparedStatementAdvice"
    );
}
```

Here, any matching `connect()` method will have `DriverAdvice` applied:

```java
@Override
public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
            nameStartsWith("connect")
                    .and(takesArgument(0, String.class))
                    .and(takesArgument(1, Properties.class))
                    .and(returns(named("java.sql.Connection"))),
            getClass().getName() + "$DriverAdvice");
}
```

### Applying Multiple Advice Classes

The `applyAdvice` method supports applying multiple advice classes to the same method matcher using varargs. This is useful when you need to apply different advices for different target systems:

```java
@Override
public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
            named("service")
                    .and(takesArgument(0, named("org.apache.coyote.Request")))
                    .and(takesArgument(1, named("org.apache.coyote.Response"))),
            getClass().getName() + "$ContextTrackingAdvice",  // Applied first
            getClass().getName() + "$ServiceAdvice"           // Applied second
    );
}
```

When multiple advices are specified, they are applied in the order they are listed. The agent will check each advice's target system compatibility (see [@AppliesOn annotation](#applieson-annotation)) and only apply advices that match the enabled target systems.

Be precise in matching to avoid inadvertently instrumenting something unintended in a current or future version of the target class.
Having multiple precise matchers is preferable to one more vague catch-all matcher which leaves some method characteristics undefined.

Instrumentation class names should end in _Instrumentation._

## Helper Classes

Classes referenced by Advice that are not provided on the bootclasspath must be defined in Helper Classes otherwise they
will not be loaded at runtime.
This includes any decorators, extractors/injectors, or wrapping classes such as tracing listeners that extend or implement
types provided by the library being instrumented. Also watch out for implicit types such as anonymous/nested classes
because they must be listed alongside the main helper class.

If an instrumentation is producing no results it may be that a required class is missing. Running muzzle

```shell
./gradlew muzzle
```

can quickly tell you if you missed a required helper class.
Messages like this in debug logs also indicate that classes are missing:

```
[MSC service thread 1-3] DEBUG datadog.trace.agent.tooling.muzzle.MuzzleCheck - Muzzled mismatch - instrumentation.names=[jakarta-mdb] instrumentation.class=datadog.trace.instrumentation.jakarta.jms.MDBMessageConsumerInstrumentation instrumentation.target.classloader=ModuleClassLoader for Module "deployment.cmt.war" from Service Module Loader muzzle.mismatch="datadog.trace.instrumentation.jakarta.jms.MessageExtractAdapter:20 Missing class datadog.trace.instrumentation.jakarta.jms.MessageExtractAdapter$1"
```

The missing class must be added in the helperClassNames method, for example:

```java
@Override
public String[] helperClassNames() {
    return new String[]{
            "datadog.trace.instrumentation.jakarta.jms.MessageExtractAdapter",
            "datadog.trace.instrumentation.jakarta.jms.JMSDecorator",
            "datadog.trace.instrumentation.jakarta.jms.MessageExtractAdapter$1"
    };
}
```

## Enums

Use care when deciding to include enums in your Advice and Decorator classes because each element of the enum will need
to be added to the helper classes individually.
For example not just `MyDecorator.MyEnum` but also `MyDecorator.MyEnum$1, MyDecorator.MyEnum$2`, etc.

## Decorator Classes

Decorators contain extra code that will be injected into the instrumented methods.

These provided Decorator classes sit
in [dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator](../dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator)

|                                                                                                                                                                                                                                                                                   |                           |                                                                                                                                                       |
|:---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------:|:-------------------------:|:-----------------------------------------------------------------------------------------------------------------------------------------------------:|
|                                                                                                                                   **Decorator**                                                                                                                                   |     **Parent Class**      |                                                        **Usage(see JavaDoc for more detail)**                                                         |
|               [`AppSecUserEventDecorator`](https://github.com/DataDog/dd-trace-java/blob/297b575f0f265c1dc78f9958e7b4b9365c80d1f9/dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/AppSecUserEventDecorator.java#L13)                |            `-`            |                                    Provides mostly login-related functions to the Spring Security instrumentation.                                    |
|                   [`AsyncResultDecorator`](https://github.com/DataDog/dd-trace-java/blob/297b575f0f265c1dc78f9958e7b4b9365c80d1f9/dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/AsyncResultDecorator.java#L18)                    |      `BaseDecorator`      |                              Handles asynchronous result types, finishing spans only when the async calls are complete.                               |
|                          [`BaseDecorator`](https://github.com/DataDog/dd-trace-java/blob/297b575f0f265c1dc78f9958e7b4b9365c80d1f9/dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/BaseDecorator.java#L21)                           |            `-`            | Provides many convenience methods related to span naming and error handling.  New Decorators should extend BaseDecorator or one of its child classes. |
|                         [`ClientDecorator`](https://github.com/DataDog/dd-trace-java/blob/297b575f0f265c1dc78f9958e7b4b9365c80d1f9/dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/ClientDecorator.java#L6)                         |      `BaseDecorator`      |                                 Parent of many Client Decorators.  Used to set client specific tags, serviceName, etc                                 |
| [`DBTypeProcessingDatabaseClientDecorator`](https://github.com/DataDog/dd-trace-java/blob/297b575f0f265c1dc78f9958e7b4b9365c80d1f9/dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/DBTypeProcessingDatabaseClientDecorator.java#L5) | `DatabaseClientDecorator` |                                       Adds automatic `processDatabaseType() `call to `DatabaseClientDecorator.`                                       |
|                [`DatabaseClientDecorator`](https://github.com/DataDog/dd-trace-java/blob/297b575f0f265c1dc78f9958e7b4b9365c80d1f9/dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/DatabaseClientDecorator.java#L14)                 |     `ClientDecorator`     |                                                         Provides general db-related methods.                                                          |
|                    [`HttpClientDecorator`](https://github.com/DataDog/dd-trace-java/blob/297b575f0f265c1dc78f9958e7b4b9365c80d1f9/dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/HttpClientDecorator.java#L23)                     | `UriBasedClientDecorator` |                                             Mostly adds span tags to HTTP client requests and responses.                                              |
|                    [`HttpServerDecorator`](https://github.com/DataDog/dd-trace-java/blob/297b575f0f265c1dc78f9958e7b4b9365c80d1f9/dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/HttpServerDecorator.java#L46)                     |     `ServerDecorator`     |                                      Adds connection and HTTP response tagging often used for server frameworks.                                      |
|                [`MessagingClientDecorator`](https://github.com/DataDog/dd-trace-java/blob/297b575f0f265c1dc78f9958e7b4b9365c80d1f9/dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/MessagingClientDecorator.java#L6)                |     `ClientDecorator`     |                                                      Adds e2e (end-to-end) duration monitoring.                                                       |
|                      [`OrmClientDecorator`](https://github.com/DataDog/dd-trace-java/blob/297b575f0f265c1dc78f9958e7b4b9365c80d1f9/dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/OrmClientDecorator.java#L5)                      | `DatabaseClientDecorator` |                                                 Set the span’s resourceName to the entityName value.                                                  |
|                         [`ServerDecorator`](https://github.com/DataDog/dd-trace-java/blob/297b575f0f265c1dc78f9958e7b4b9365c80d1f9/dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/ServerDecorator.java#L7)                         |      `BaseDecorator`      |                                                     Adding server and language tags to the span.                                                      |
|                 [`UriBasedClientDecorator`](https://github.com/DataDog/dd-trace-java/blob/297b575f0f265c1dc78f9958e7b4b9365c80d1f9/dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/UriBasedClientDecorator.java#L9)                 |     `ClientDecorator`     |                                         Adds hostname, port and service values from URIs to HttpClient spans.                                         |
|                 [`UrlConnectionDecorator`](https://github.com/DataDog/dd-trace-java/blob/297b575f0f265c1dc78f9958e7b4b9365c80d1f9/dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/UrlConnectionDecorator.java#L18)                  | `UriBasedClientDecorator` |                     Sets some tags based on URI and URL values.  Also provides some caching.  Only used by `UrlInstrumentation`.                      |

Instrumentations often include their own Decorators which extend those classes, for example:

|                            |                                                                                                                                                                                                                                                |                                                                                                                                                                                                                                                     |
|:--------------------------:|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------:|:---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------:|
|    **Instrumentation**     |                                                                                                                 **Decorator**                                                                                                                  |                                                                                                                  **Parent Class**                                                                                                                   |
|            JDBC            |                           [`DataSourceDecorator`](https://github.com/DataDog/dd-trace-java/blob/master/dd-java-agent/instrumentation/jdbc/src/main/java/datadog/trace/instrumentation/jdbc/DataSourceDecorator.java)                           |                              [`BaseDecorator`](https://github.com/DataDog/dd-trace-java/blob/master/dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/BaseDecorator.java)                               |
|          RabbitMQ          | [`RabbitDecorator`](https://github.com/DataDog/dd-trace-java/blob/297b575f0f265c1dc78f9958e7b4b9365c80d1f9/dd-java-agent/instrumentation/rabbitmq-amqp-2.7/src/main/java/datadog/trace/instrumentation/rabbitmq/amqp/RabbitDecorator.java#L34) | [`MessagingClientDecorator`](https://github.com/DataDog/dd-trace-java/blob/297b575f0f265c1dc78f9958e7b4b9365c80d1f9/dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/MessagingClientDecorator.java#L6) |
| All HTTP Server frameworks |                                                                                                                    various                                                                                                                     |     [`HttpServerDecorator`](https://github.com/DataDog/dd-trace-java/blob/297b575f0f265c1dc78f9958e7b4b9365c80d1f9/dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/instrumentation/decorator/HttpServerDecorator.java#L46)      |

Decorator class names must be in the instrumentation's helper classes since Decorators need to be loaded with the
instrumentation.

Decorator class names should end in _Decorator._

## Advice Classes

Byte Buddy injects compiled bytecode at runtime to wrap existing methods, so they communicate with Datadog at entry or exit.
These modifications are referred to as _advice transformation_ or just _advice_.

Instrumenters register advice transformations by calling `AdviceTransformation.applyAdvice(ElementMatcher, String)` 
and Methods are matched by the instrumentation's `adviceTransformations()` method.

The Advice is injected into the type so Advice can only refer to those classes on the bootstrap class-path or helpers
injected into the application class-loader.
Advice must not refer to any methods in the instrumentation class or even other methods in the same advice class because
the advice is really only a template of bytecode to be inserted into the target class.
It is only the advice bytecode (plus helpers) that is copied over.
The rest of the instrumenter and advice class is ignored.
Do not place code in the Advice constructor because the constructor is never called.

You can not use methods like `InstrumentationContext.get()` outside of the instrumentation advice because the tracer
currently patches the method stub with the real call at runtime.
But you can pass the ContextStore into a helper/decorator like in [DatadogMessageListener](https://github.com/DataDog/dd-trace-java/blob/743bacde52ba4369e05631436168bfde9b815c8b/dd-java-agent/instrumentation/jms/src/main/java/datadog/trace/instrumentation/jms/DatadogMessageListener.java).
This could reduce duplication if you re-used the helper.
But unlike most applications, some duplication can be the better choice in the tracer if it simplifies things and reduces overhead.
You might end up with very similar code scattered around, but it will be simple to maintain.
Trying to find an abstraction that works well across instrumentations can take time and may introduce extra indirection.

Advice classes provide the code to be executed before and/or after a matched method.
The classes use a static method annotated by `@Advice.OnMethodEnter` and/or `@Advice.OnMethodExit` to provide the code.
The method name is irrelevant.

A method that is annotated with `@Advice.OnMethodEnter `can annotate its parameters with `@Advice.Argument`.
`@Advice.Argument` will substitute this parameter with the corresponding argument of the instrumented method.
This allows the `@Advice.OnMethodEnter` code to see and modify the parameters that would be passed to the target method.

Alternatively, a parameter can be annotated by `Advice.This` where the `this` reference of the instrumented method is
assigned to the new parameter.
This can also be used to assign a new value to the `this` reference of an instrumented method.

If no annotation is used on a parameter, it is assigned the n-th parameter of the instrumented method for the n-th
parameter of the advice method.
Explicitly specifying which parameter is intended is recommended to be more clear, for example:

`@Advice.Argument(0) final HttpUriRequest request`

All parameters must declare the exact same type as the parameters of the instrumented type or the method's declaring
type for `Advice.This`.
If they are marked as read-only, then the parameter type may be a super type of the original.

A method that is annotated with `Advice.OnMethodExit` can also annotate its parameters with `Advice.Argument`
and `Advice.This`.
It can also annotate a parameter with `Advice.Return` to receive the original method's return value.
By reassigning the return value, it can replace the returned value.
If an instrumented method does not return a value, this annotation must not be used.
If a method throws an exception, the parameter is set to its default value (0 for primitive types and null for reference types).
The parameter's type must equal the instrumented method's return type if it is not set to read-only.
If the parameter is read-only it may be a super type of the instrumented method's return type.

Advice class names should end in _Advice._

### @AppliesOn Annotation

The `@AppliesOn` annotation allows you to override which target systems a specific advice class applies to, independent of the InstrumenterModule's target system. This is useful when you have an instrumentation module that extends one target system (e.g., `InstrumenterModule.Tracing`), but want certain advice classes to also be applied for other target systems.

#### Usage

Annotate your advice class with `@AppliesOn` and specify the target systems where this advice should be applied:

```java
import datadog.trace.agent.tooling.InstrumenterModule.TargetSystem;
import datadog.trace.agent.tooling.annotation.AppliesOn;

@AppliesOn(TargetSystem.CONTEXT_TRACKING)
public static class ContextTrackingAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void extractParent(
            @Advice.Argument(0) org.apache.coyote.Request req,
            @Advice.Local("parentScope") ContextScope parentScope) {
        // This advice only runs when CONTEXT_TRACKING is enabled
        final Context parentContext = DECORATE.extract(req);
        parentScope = parentContext.attach();
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void closeScope(@Advice.Local("parentScope") ContextScope scope) {
        scope.close();
    }
}
```

#### When to Use @AppliesOn

Use `@AppliesOn` when:

1. **Selective Advice Application**: You want different advice classes within the same instrumentation to apply to different target systems. For example, an instrumentation might extend `InstrumenterModule.Tracing` but have some advice that should only run for `CONTEXT_TRACKING`.

2. **Multi-System Support**: Your instrumentation needs to work across multiple target systems with different behaviors for each. By applying multiple advices with different `@AppliesOn` annotations, you can customize behavior per target system.

3. **Separating Concerns**: You want to cleanly separate context tracking logic from tracing logic in the same instrumentation, making the code more maintainable.

#### Example: Tomcat Server Instrumentation

In the Tomcat instrumentation, we apply both context tracking and tracing advices to the same method:

```java
@Override
public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
            named("service")
                    .and(takesArgument(0, named("org.apache.coyote.Request")))
                    .and(takesArgument(1, named("org.apache.coyote.Response"))),
            TomcatServerInstrumentation.class.getName() + "$ContextTrackingAdvice",
            TomcatServerInstrumentation.class.getName() + "$ServiceAdvice"
    );
}
```

The `ContextTrackingAdvice` is annotated with `@AppliesOn(TargetSystem.CONTEXT_TRACKING)`, so it only runs when context tracking is enabled. The `ServiceAdvice` (without the annotation) runs when the module's target system (`TRACING`) is enabled.

#### Important Notes

- If an advice class does not have the `@AppliesOn` annotation, it will be applied whenever the parent InstrumenterModule's target system is enabled.
- When multiple advices are applied to the same method, they are applied in the order specified, and each one's target system compatibility is checked individually.

## Exceptions in Advice

Advice methods are typically annotated like

`@Advice.OnMethodEnter(suppress = Throwable.class)`

and

`@Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)`

Using `suppress = Throwable.class` is considered our default for both methods unless there is a reason not to suppress.
It means the exception handler is triggered on any exception thrown within the Advice, which terminates the Advice method.
The opposite would be either no `suppress` annotation or equivalently `suppress = NoExceptionHandler.class` which would 
allow exceptions in Advice code to surface and is usually undesirable.

> [!NOTE]
> Don't use `suppress` on an advice hooking a constructor.
> For older JVMs that do not support [flexible constructor bodies](https://openjdk.org/jeps/513), you can't decorate the
> mandatory self or parent constructor call with try/catch, as it must be the first call from the constructor body.

If
the [`Advice.OnMethodEnter`](https://javadoc.io/static/net.bytebuddy/byte-buddy/1.10.2/net/bytebuddy/asm/Advice.OnMethodEnter.html)
method throws an exception,
the [`Advice.OnMethodExit`](https://javadoc.io/static/net.bytebuddy/byte-buddy/1.10.2/net/bytebuddy/asm/Advice.OnMethodExit.html)
method is not invoked.

The [`Advice.Thrown`](https://javadoc.io/static/net.bytebuddy/byte-buddy/1.10.2/net/bytebuddy/asm/Advice.Thrown.html)
annotation passes any thrown exception from the instrumented method to
the [`Advice.OnMethodExit`](https://javadoc.io/static/net.bytebuddy/byte-buddy/1.10.2/net/bytebuddy/asm/Advice.OnMethodExit.html)
advice
method.  
[`Advice.Thrown`](https://javadoc.io/static/net.bytebuddy/byte-buddy/1.10.2/net/bytebuddy/asm/Advice.Thrown.html) ****
should annotate at most one parameter on the exit advice.

If the instrumented method throws an exception,
the [Advice.OnMethodExit](https://javadoc.io/static/net.bytebuddy/byte-buddy/1.10.2/net/bytebuddy/asm/Advice.OnMethodExit.html)
method is still invoked unless
the [Advice.OnMethodExit.onThrowable()](https://javadoc.io/static/net.bytebuddy/byte-buddy/1.10.2/net/bytebuddy/asm/Advice.OnMethodExit.html#onThrowable--)
property is set to false. If this property is set to false,
the [Advice.Thrown](https://javadoc.io/static/net.bytebuddy/byte-buddy/1.10.2/net/bytebuddy/asm/Advice.Thrown.html)
annotation must not be used on any parameter.

If an instrumented method throws an exception, the return parameter is set to its default of 0 for primitive types or
null for reference types.
An exception can be read by annotating an exit
method’s [Throwable](http://docs.oracle.com/javase/1.5.0/docs/api/java/lang/Throwable.html?is-external=true) parameter
with [Advice.Thrown](https://javadoc.io/static/net.bytebuddy/byte-buddy/1.10.2/net/bytebuddy/asm/Advice.Thrown.html)
which is assigned the
thrown [Throwable](http://docs.oracle.com/javase/1.5.0/docs/api/java/lang/Throwable.html?is-external=true) or null if a
method returns normally. This allows exchanging a thrown exception with any checked or unchecked exception.
For example, either the result or the exception will be passed to the helper method here:

```java
@Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
public static void methodExit(
        @Advice.Return final Object result,
        @Advice.Thrown final Throwable throwable
) {
    HelperMethods.doMethodExit(result, throwable);
}
```

## Logging in Instrumentations

Logging should only be used in helper classes where you can easily add and access a static logger field:

```java
// GOOD - Logger only in helper classes
public class MyInstrumentationHelper {
  private static final Logger log = LoggerFactory.getLogger(MyInstrumentationHelper.class);

  public void helperMethod() {
    log.debug("Logging from helper is safe");
    // This helper is called from instrumentation/advice
  }
}
```

`org.slf4j` is the logging facade to use.
It is shaded and redirects to our internal logger.

> [!CAUTION]
> Do NOT put logger fields in instrumentation classes:

```java
// BAD - Logger in instrumentation class
public class MyInstrumentation extends InstrumenterModule.Tracing {
  private static final Logger log = LoggerFactory.getLogger(MyInstrumentation.class);
}
```

> [!CAUTION]
> Do NOT put logger fields in Advice classes:

```java
// BAD - Logger in advice class
public class MyAdvice {
  private static final Logger log = LoggerFactory.getLogger(MyAdvice.class);

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter() {
    log.debug("Entering method"); // BAD
  }
}
```

## InjectAdapters & Custom GETTERs/SETTERs

Custom Inject Adapter static instances typically named `SETTER` implement the `AgentPropagation.Setter` interface and
are used to normalize setting shared context values such as in HTTP headers.

Custom inject adapter static instances typically named `GETTER` implement the `AgentPropagation.Getter` interface and
are used to normalize extracting shared context values such as from HTTP headers.

For example `google-http-client` sets its header values using:

`com.google.api.client.http.HttpRequest.getHeaders().put(key,value)`

```java
package datadog.trace.instrumentation.googlehttpclient;

import com.google.api.client.http.HttpRequest;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;

public class HeadersInjectAdapter implements AgentPropagation.Setter<HttpRequest> {
    public static final HeadersInjectAdapter SETTER = new HeadersInjectAdapter();

    @Override
    public void set(final HttpRequest carrier, final String key, final String value) {
        carrier.getHeaders().put(key, value);
    }
}
```

But notice `apache-http-client5` sets its header values using:

`org.apache.hc.core5.http.HttpRequest.setHeader(key,value)`

```java
package datadog.trace.instrumentation.apachehttpclient5;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.apache.hc.core5.http.HttpRequest;

public class HttpHeadersInjectAdapter implements AgentPropagation.Setter<HttpRequest> {
    public static final HttpHeadersInjectAdapter SETTER = new HttpHeadersInjectAdapter();

    @Override
    public void set(final HttpRequest carrier, final String key, final String value) {
        carrier.setHeader(key, value);
    }
}
```

These implementation-specific methods are both wrapped in a standard set(...) method by the SETTER.

## To Wrap or Not To Wrap?

Typically, an instrumentation will use ByteBuddy to apply new code from an Advice class before and/or after the targeted
code using `@Advice.OnMethodEnter` and `@Advice.OnMethodExit.`

Alternatively, you can replace the call to the target method with your own code which wraps the original method call.
An example is the JMS Instrumentation which replaces the `MessageListener.onMessage()` method
with `DatadogMessageListener.onMessage()`. 
The `DatadogMessageListener` then [calls the original `onMessage()` method](https://github.com/DataDog/dd-trace-java/blob/9a28dc3f0333e781b2defc378c9020bf0a44ee9a/dd-java-agent/instrumentation/jms/src/main/java/datadog/trace/instrumentation/jms/DatadogMessageListener.java#L73).
Note that this style is **_not recommended_** because it can cause datadog packages to appear in stack traces generated
by errors in user code. This has created confusion in the past.

## Context Stores

Context stores pass information between instrumented methods, using library objects that both methods have access to.
They can be used to attach data to a request when the request is received, and read that data where the request is
deserialized.
Context stores work internally by dynamically adding a field to the “carrier” object by manipulating the bytecode.
Since they manipulate bytecode, context stores can only be created within Advice classes.
For example:

```java
ContextStore<X> store = InstrumentationContext.get(
        "com.amazonaws.services.sqs.model.ReceiveMessageResult", "java.lang.String");
```

It’s also possible to pass the types as class objects, but this is only possible for classes that are in the bootstrap
classpath.
Basic types like `String` would work and the usual datadog types like `AgentSpan` are OK too, but classes from the
library you are instrumenting are not.

In the example above, that context store is used to store an arbitrary `String` in a `ReceiveMessageResult` class.
It is used like a Map:

```java
store.put(response, "my string");
```

and/or

```java
String stored = store.get(response); // "my string"
```

Context stores also need to be pre-declared in the Advice by overriding the `contextStore()` method otherwise, using
them throws exceptions.

```java
@Override
public Map<String, String> contextStore() {
    return singletonMap(
            "com.amazonaws.services.sqs.model.ReceiveMessageResult",
            "java.lang.String"
    );
}
```

It is important to understand that even though they look like maps, since the value is stored in the key, you can only
retrieve a value if you use the exact same key object as when it was set.
Using a different object that is “`.equals()`” to the first will yield nothing.

Since `ContextStore` does not support null keys, null checks must be enforced _before_ using an object as a key.

## CallDepthThreadLocalMap

In order to avoid activating new spans on recursive calls to the same method
a [CallDepthThreadLocalMap](https://github.com/DataDog/dd-trace-java/blob/9d5c7ea524cfec982176e687a489fc8c2865e445/dd-java-agent/agent-bootstrap/src/main/java/datadog/trace/bootstrap/CallDepthThreadLocalMap.java#L11)
is often used to determine if a call is recursive by using a counter. It is incremented with each call to the method
and [decremented](https://github.com/DataDog/dd-trace-java/blob/9d5c7ea524cfec982176e687a489fc8c2865e445/dd-java-agent/instrumentation/vertx-redis-client-3.9/src/main/java/datadog/trace/instrumentation/vertx_redis_client/RedisSendAdvice.java#L82) (
or [reset](https://github.com/DataDog/dd-trace-java/blob/9d5c7ea524cfec982176e687a489fc8c2865e445/dd-java-agent/instrumentation/java-http-client/src/main/java11/datadog/trace/instrumentation/httpclient/SendAdvice.java#L44))
when exiting.

This only works if the methods are called on the same thread since the counter is a ThreadLocal variable.

## Span Lifecycle

In Advice classes, the `@Advice.OnMethodEnter` methods typically start spans and `@Advice.OnMethodExit` methods
typically finish spans.

Starting the span may be done directly or with helper methods which eventually make a call to one of the
various `AgentTracer.startSpan(...)` methods.

Finishing the span is normally done by calling `span.finish()` in the exit method;

The basic span lifecycle in an Advice class looks like:

1. Start the span
2. Decorate the span
3. Activate the span and get the AgentScope
4. Run the instrumented target method
5. Close the Agent Scope
6. Finish the span

```java
@Advice.OnMethodEnter(suppress = Throwable.class)
public static AgentScope begin() {
    final AgentSpan span = startSpan(/* */);
    DECORATE.afterStart(span);
    return activateSpan(span);
}

@Advice.OnMethodExit(suppress = Throwable.class)
public static void end(@Advice.Enter final AgentScope scope) {
    AgentSpan span = scope.span();
    DECORATE.beforeFinish(span);
    scope.close();
    span.finish();
}
```

For example,
the [`HttpUrlConnectionInstrumentation`](https://github.com/DataDog/dd-trace-java/blob/4d0b113c4c9dc23ef2a44d30952d38d09ff28ff3/dd-java-agent/instrumentation/http-url-connection/src/main/java/datadog/trace/instrumentation/http_url_connection/HttpUrlConnectionInstrumentation.java#L26)
class contains
the [`HttpUrlConnectionAdvice`](https://github.com/DataDog/dd-trace-java/blob/4d0b113c4c9dc23ef2a44d30952d38d09ff28ff3/dd-java-agent/instrumentation/http-url-connection/src/main/java/datadog/trace/instrumentation/http_url_connection/HttpUrlConnectionInstrumentation.java#L66)
class which calls
the `HttpUrlState.`[`start`](https://github.com/DataDog/dd-trace-java/blob/4d0b113c4c9dc23ef2a44d30952d38d09ff28ff3/dd-java-agent/instrumentation/http-url-connection/src/main/java/datadog/trace/instrumentation/http_url_connection/HttpUrlConnectionInstrumentation.java#L84)`()`
and `HttpUrlState.`[`finishSpan`](https://github.com/DataDog/dd-trace-java/blob/4d0b113c4c9dc23ef2a44d30952d38d09ff28ff3/dd-java-agent/instrumentation/http-url-connection/src/main/java/datadog/trace/instrumentation/http_url_connection/HttpUrlConnectionInstrumentation.java#L113)`()`
methods.

## Continuations

- [`AgentScope.Continuation`](https://github.com/DataDog/dd-trace-java/blob/09ac78ff0b54fbbbee0ab1c89c901d2043fda40b/dd-trace-api/src/main/java/datadog/trace/context/TraceScope.java#L47)
  is used to pass context between threads.
- Continuations must be either activated or canceled.
- If a Continuation is activated it returns a TraceScope which must eventually be closed.
- Only after all TraceScopes are closed and any non-activated Continuations are canceled may the Trace finally close.

Notice
in [`HttpClientRequestTracingHandler`](https://github.com/DataDog/dd-trace-java/blob/3fe1b2d6010e50f61518fa25af3bdeb03ae7712b/dd-java-agent/instrumentation/netty-4.1/src/main/java/datadog/trace/instrumentation/netty41/client/HttpClientRequestTracingHandler.java#L56)
how the AgentScope.Continuation is used to obtain the `parentScope` which is
finally [closed](https://github.com/DataDog/dd-trace-java/blob/3fe1b2d6010e50f61518fa25af3bdeb03ae7712b/dd-java-agent/instrumentation/netty-4.1/src/main/java/datadog/trace/instrumentation/netty41/client/HttpClientRequestTracingHandler.java#L111).

## Naming

### Gradle Module Names

Instrumentation Gradle modules must follow these naming conventions (enforced by the `dd-trace-java.instrumentation-naming` plugin):

1. **Version or Suffix Requirement**: Module names must end with either:
   - A version number (e.g., `2.0`, `3.1`, `3.1.0`)
   - A configured suffix (i.e.: `-common` for shared classes, or product dependent like `-iast`)

   Examples:
   - `couchbase-2.0` ✓
   - `couchbase-3.1.0` ✓
   - `couchbase-common` ✓
   - `couchbase` ✗ (missing version or suffix)

2. **Parent Directory Name**: Module names must contain their parent directory name.

   Examples:
   - Parent: `couchbase`, Module: `couchbase-2.0` ✓ (contains couchbase)
   - Parent: `couchbase`, Module: `couch-2.0` ✗ 

3. **Exclusions**: Modules under `:dd-java-agent:instrumentation:datadog` are automatically excluded from these rules
since they are not related to a third party library version. 
They contain instrumentation modules related to internal datadog features, and they are classified by product.
Examples are: `trace-annotation` (supporting the `tracing` product) or `enable-wallclock-profiling`.

The naming rules can be checked when running `./gradlew checkInstrumentationNaming`.

### Class and Package Names

- Instrumentation names use kebab case. For example: `google-http-client`
- Instrumentation module name and package name should be consistent.
  For example, the instrumentation `google-http-client` contains the `GoogleHttpClientInstrumentation` class in the
  package `datadog.trace.instrumentation.googlehttpclient`.
- As usual, class names should be nouns, in camel case with the first letter of each internal word capitalized.
  Use whole words-avoid acronyms and abbreviations (unless the abbreviation is much more widely used than the long form,
  such as URL or HTML).
- Advice class names should end in _Advice._
- Instrumentation class names should end in _Instrumentation._
- Decorator class names should end in _Decorator._

## Tooling

### ignored\_class\_name.trie

The file [ignored\_class\_name.trie](../dd-java-agent/agent-tooling/src/main/resources/datadog/trace/agent/tooling/bytebuddy/matcher/ignored_class_name.trie)
lists classes that are to be globally ignored by matchers because they are unsafe, pointless or expensive to transform.
If you notice an expected class is not being transformed, it may be covered by an entry in this list.

## GraalVM

Instrumentations running on GraalVM should avoid using reflection if possible.
If reflection must be used the reflection usage should be added to
`dd-java-agent/agent-bootstrap/src/main/resources/META-INF/native-image/com.datadoghq/dd-java-agent/reflect-config.json`.

See [GraalVM configuration docs](https://www.graalvm.org/jdk17/reference-manual/native-image/dynamic-features/Reflection/#manual-configuration).

## Testing

### Instrumentation Tests

Tests are written in Groovy using the [Spock framework](http://spockframework.org).
For instrumentations, `InstrumentationSpecification` must be extended.
For example, HTTP server frameworks use base tests which enforce consistency between different implementations
(see [HttpServerTest](../dd-java-agent/testing/src/main/groovy/datadog/trace/agent/test/base/HttpServerTest.groovy)).
When writing an instrumentation it is much faster to test just the instrumentation rather than build the entire project,
for example:

```shell
./gradlew :dd-java-agent:instrumentation:play-ws:play-ws-2.1:test
```

Sometimes it is necessary to force Gradle to discard cached test results and [rerun all tasks](https://docs.gradle.org/current/userguide/command_line_interface.html#sec:rerun_tasks).

```shell
./gradle test --rerun-tasks
```

Running tests that require JDK-21 can use the `-PtestJvm=21` flag (if not installed, Gradle will provision them),
for example:

```shell
./gradlew  :dd-java-agent:instrumentation:aerospike-4.0:allLatestDepTests -PtestJvm=21
```

> [!TIP]
> The `testJvm` property also accept a path to a JVM home. E.g.
> 
> ```shell
> /gradlew  :dd-java-agent:instrumentation:an-insturmentation:test -PtestJvm=~/.local/share/mise/installs/java/openjdk-26.0.0-loom+1/
> ```

### Latest Dependency Tests

Adding a directive to the build file gives early warning when breaking changes are released by framework maintainers.
For example, for Play 2.5, we download the latest dependency and run tests against it:

```groovy
latestDepTestCompile group: 'com.typesafe.play', name: 'play-java_2.11', version: '2.5.+'

latestDepTestCompile group: 'com.typesafe.play', name: 'play-java-ws_2.11', version: '2.5.+'

latestDepTestCompile(group: 'com.typesafe.play', name: 'play-test_2.11', version: '2.5.+') {
  exclude group: 'org.eclipse.jetty.websocket', module: 'websocket-client'
}
```

Dependency tests can be run like:

```shell
./gradlew :dd-java-agent:instrumentation:play-ws:play-ws-2.1:latestDepTest
```

### Additional Test Suites

The file [dd-trace-java/gradle/test-suites.gradle](../gradle/test-suites.gradle)
contains these macros for adding different test suites to individual instrumentation builds.
Notice how `addTestSuite` and `addTestSuiteForDir` pass values to [`addTestSuiteExtendingForDir`](https://github.com/DataDog/dd-trace-java/blob/c3ea017590f10941232bbb0f694525bf124d4b49/gradle/test-suites.gradle#L3)
which configures the tests.

```groovy
ext.addTestSuite = (String testSuiteName) -> {
  ext.addTestSuiteForDir(testSuiteName, testSuiteName)
}

ext.addTestSuiteForDir = (String testSuiteName, String dirName) -> {
  ext.addTestSuiteExtendingForDir(testSuiteName, 'test', dirName)
}

ext.addTestSuiteExtendingForDir = (String testSuiteName, String parentSuiteName, String dirName) -> { /* */ }
```

For example:

```groovy
addTestSuite('latestDepTest')
```

Also, the forked test for latestDep is not run by default without declaring something like:

```groovy
addTestSuiteExtendingForDir('latestDepForkedTest', 'latestDepTest', 'test')
```

(also example [`vertx-web-3.5/build.gradle`](https://github.com/DataDog/dd-trace-java/blob/c3ea017590f10941232bbb0f694525bf124d4b49/dd-java-agent/instrumentation/vertx-web-3.5/build.gradle#L18)`)`

### Smoke Tests

In addition to unit tests, [Smoke tests](../dd-smoke-tests) may be needed.
Smoke tests run with a real agent jar file set as the `javaagent`.
These are optional and not all frameworks have them, but contributions are very welcome.

# Summary

Integrations have evolved over time.
Newer examples of integrations such as Spring and JDBC illustrate current best practices.

# Additional Reading

- Datadog Instrumentations rely heavily on ByteBuddy. You may find the
  ByteBuddy [tutorial](https://bytebuddy.net/#/tutorial) useful.
- The [Groovy docs](https://groovy-lang.org/single-page-documentation.html).
- [Spock Framework Reference Documentation](https://spockframework.org/spock/docs/2.3/index.html).
