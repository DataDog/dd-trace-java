# Add a New Instrumentation

Now we will step through adding a very basic instrumentation to the trace agent. The
existing [google-http-client instrumentation](../dd-java-agent/instrumentation/google-http-client)
will be used as an example.

## Clone the dd-trace-java repo

```shell
git clone https://github.com/DataDog/dd-trace-java.git
```

## Name your instrumentation

Follow existing naming conventions for instrumentations. In this case, the instrumentation is
named `google-http-client`. (see [Naming](./how_instrumentations_work.md#naming))

## Configuring Gradle

Add the new instrumentation to [`settings.gradle`](../settings.gradle)
in alpha order with the other instrumentations in this format:

```groovy
include ':dd-java-agent:instrumentation:$framework?:$framework-$minVersion'
```

In this case
we [added](https://github.com/DataDog/dd-trace-java/blob/297b575f0f265c1dc78f9958e7b4b9365c80d1f9/settings.gradle#L209C3-L209C3):

```groovy
include ':dd-java-agent:instrumentation:google-http-client'
```

## Create the Instrumentation class

1. Choose an appropriate package name for the instrumentation
   like `package datadog.trace.instrumentation.googlehttpclient.` (see [Naming](./how_instrumentations_work.md#naming))
2. Create an appropriate directory structure for your instrumentation which agrees with the package name. (
   see [Files and Directories](./how_instrumentations_work.md#filesdirectories))
3. Choose an appropriate class name
   like `datadog.trace.instrumentation.googlehttpclient.GoogleHttpClientInstrumentation` (
   see [Naming](./how_instrumentations_work.md#naming))
4. Include the required `@AutoService(InstrumenterModule.class) `annotation.
5. Choose an appropriate parent class for the instrumentation:
   - `InstrumenterModule.Tracing` for tracing instrumentations (most common)
   - `InstrumenterModule.ContextTracking` for context propagation only (without creating spans)
   - Or any other target system as appropriate (see [Instrumentation classes](./how_instrumentations_work.md#instrumentation-classes))
6. Since this instrumentation class will only modify one specific type, it can implement
   the `Instrumenter.ForSingleType `interface which provides the `instrumentedType()` method. (
   see [Type Matching](./how_instrumentations_work.md#type-matching))
7. Pass the instrumentation name to the superclass constructor

```java

@AutoService(InstrumenterModule.class)
public class GoogleHttpClientInstrumentation extends InstrumenterModule.Tracing implements Instrumenter.ForSingleType {
    public GoogleHttpClientInstrumentation() {
        super("google-http-client");
    }
    // ...
}
```

## Match the target class

In this case we target only one known class to instrument. This is the class which contains the method this
instrumentation should modify. (see [Type Matching](./how_instrumentations_work.md#type-matching))

```java

@Override
public String instrumentedType() {
    return "com.google.api.client.http.HttpRequest";
}
```

## Match the target method

We want to apply advice to
the [`HttpRequest.execute()`](https://github.com/googleapis/google-http-java-client/blob/1acedf75368f11ab03e5f84dd2c58a8a8a662d41/google-http-client/src/main/java/com/google/api/client/http/HttpRequest.java#L849)
method. It has this signature:

```java
public HttpResponse execute() throws IOException {/* */}
```

Target the method using [appropriate Method Matchers](./how_instrumentations_work.md#method-matching) and include the
name String to be used for the Advice class when calling `transformation.applyAdvice()`:

```java
public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
            isMethod()
                    .and(isPublic())
                    .and(named("execute"))
                    .and(takesArguments(0)),
            GoogleHttpClientInstrumentation.class.getName() + "$GoogleHttpClientAdvice"
    );
}
```

### Applying Multiple Advices (Advanced)

If you need to apply multiple advice classes to the same method (for example, to separate context tracking from tracing logic), you can pass multiple advice class names to `applyAdvices()`:

```java
public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvices(
            named("service")
                    .and(takesArgument(0, named("org.apache.coyote.Request")))
                    .and(takesArgument(1, named("org.apache.coyote.Response"))),
            getClass().getName() + "$ContextTrackingAdvice",  // Applied first
            getClass().getName() + "$ServiceAdvice"           // Applied second
    );
}
```

When applying multiple advices, consider using the `@AppliesOn` annotation to control which target systems each advice applies to. This allows different advices to run for different target systems (e.g., one for `CONTEXT_TRACKING`, another for `TRACING`). See the [@AppliesOn section](./how_instrumentations_work.md#applieson-annotation) for more details.

## Add the HeadersInjectAdapter

This particular instrumentation uses
a [HeadersInjectAdapter](../dd-java-agent/instrumentation/google-http-client/src/main/java/datadog/trace/instrumentation/googlehttpclient/HeadersInjectAdapter.java)
class to assist with HTTP header injection. This is not required of all instrumentations. (
See [InjectorAdapters](./how_instrumentations_work.md#injectadapters--custom-getterssetters)).

```java
public class HeadersInjectAdapter {
    @Override
    public void set(final HttpRequest carrier, final String key, final
    String value) {
        carrier.getHeaders().put(key, value);
    }
}
```

## Create a Decorator class

1. The class name should end in Decorator.  `GoogleHttpClientDecorator `is good.
2. Since this is an HTTP client instrumentation, the class should extend  `HttpClientDecorator.`
3. Override the methods as needed to provide behaviors specific to this instrumentation. For
   example `getResponseHeader()` and `getRequestHeader()` require functionality specific to the Google `HttpRequest`
   and `HttpResponse` classes used when declaring this Decorator class:
    1. `public class GoogleHttpClientDecorator extends HttpClientDecorator<HttpRequest, HttpResponse> {/* */}`
    2. Instrumentations of other HTTP clients would declare Decorators that extend the same HttpClientDecorator but
       using their own Request and Response classes instead.
4. Typically, we create one static instance of the Decorator named `DECORATE`.
5. For efficiency, create and retain frequently used CharSequences such as `GOOGLE_HTTP_CLIENT` and `HTTP_REQUEST`, etc.
6. Add methods like `prepareSpan()` that will be called
   from [multiple](https://github.com/DataDog/dd-trace-java/blob/5307b46fe3956f0d1f09f84e1dab580af222ddc5/dd-java-agent/instrumentation/google-http-client/src/main/java/datadog/trace/instrumentation/googlehttpclient/GoogleHttpClientInstrumentation.java#L75)
   different [places](https://github.com/DataDog/dd-trace-java/blob/5307b46fe3956f0d1f09f84e1dab580af222ddc5/dd-java-agent/instrumentation/google-http-client/src/main/java/datadog/trace/instrumentation/googlehttpclient/GoogleHttpClientInstrumentation.java#L103)
   to reduce code duplication. Confining extensive tag manipulation to the Decorators also makes the Advice class easier
   to understand and maintain.

```java
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;

public class GoogleHttpClientDecorator
        extends HttpClientDecorator<HttpRequest, HttpResponse> {
    private static final Pattern URL_REPLACEMENT = Pattern.compile("%20");
    public static final CharSequence GOOGLE_HTTP_CLIENT =
            UTF8BytesString.create("google-http-client");
    public static final GoogleHttpClientDecorator DECORATE = new
            GoogleHttpClientDecorator();
    public static final CharSequence HTTP_REQUEST =
            UTF8BytesString.create(DECORATE.operationName());

    @Override
    protected String method(final HttpRequest httpRequest) {
        return httpRequest.getRequestMethod();
    }

    @Override
    protected URI url(final HttpRequest httpRequest) throws URISyntaxException {
        final String url = httpRequest.getUrl().build();
        final String fixedUrl = URL_REPLACEMENT.matcher(url).replaceAll("+");
        return URIUtils.safeParse(fixedUrl);
    }

    public AgentSpan prepareSpan(AgentSpan span, HttpRequest request) {
        DECORATE.afterStart(span);
        DECORATE.onRequest(span, request);
        propagate().inject(span, request, SETTER);
        propagate().injectPathwayContext(span, request, SETTER,
                HttpClientDecorator.CLIENT_PATHWAY_EDGE_TAGS);
        return span;
    }

    @Override
    protected int status(final HttpResponse httpResponse) {
        return httpResponse.getStatusCode();
    }

    @Override
    protected String[] instrumentationNames() {
        return new String[]{"google-http-client"};
    }

    @Override
    protected CharSequence component() {
        return GOOGLE_HTTP_CLIENT;
    }

    @Override
    protected String getRequestHeader(HttpRequest request, String headerName) {
        return request.getHeaders().getFirstHeaderStringValue(headerName);
    }

    @Override
    protected String getResponseHeader(HttpResponse response,
                                       String headerName) {
        return response.getHeaders().getFirstHeaderStringValue(headerName);
    }
}
```

## Add helper class names

The `GoogleHttpClientDecorator` and `HeadersInjectAdapter` class names must be included in helper classes defined in the
Instrumentation class, or they will not be available at runtime.  `packageName` is used for convenience but helper
classes outside the current package could also be included.

```java

@Override
public String[] helperClassNames() {
    return new String[]{
            packageName + ".GoogleHttpClientDecorator",
            packageName + ".HeadersInjectAdapter"
    };
}
```

## Add Advice class

1. Add a new static class to the Instrumentation class. The name must match what was passed to
   the `adviceTransformations()` method earlier, here `GoogleHttpClientAdvice.`
2. Create two static methods named whatever you like.  `methodEnter` and `methodExit` are good choices. These **must**
   be static.
3. With `methodEnter:`
    1. Annotate the method using `@Advice.OnMethodEnter(suppress = Throwable.class) `(
       see [Exceptions in Advice](./how_instrumentations_work.md#exceptions-in-advice))
    2. Add parameter `@Advice.This HttpRequest request`. It will point to the target `execute()` method’s _this_
       reference which must be of the same `HttpRequest` type.
    3. Add a parameter, `@Advice.Local("inherited") boolean inheritedScope`. This shared local variable will be visible
       to both `OnMethodEnter` and `OnMethodExit` methods.
    4. Use `activeScope()` __to __see if an `AgentScope` is already active. If so, return that `AgentScope`, but first
       let the exit method know by setting the shared `inheritedScope` boolean.
    5. If an `AgentScope` was not active then start a new span, decorate it, activate it and return it.
4. With `methodExit:`
    1. Annotate the method using `@Advice.OnMethodExit(onThrowable=Throwable.class, suppress=Throwable.class). `(
       see [Exceptions in Advice](./how_instrumentations_work.md#exceptions-in-advice))
    2. Add parameter `@Advice.Enter AgentScope scope. `This is the `AgentScope` object returned earlier
       by `methodEnter()`. Note this is not the return value of the target `execute()` method.
    3. Add a parameter, `@Advice.Local("inherited") boolean inheritedScope`. This is the shared local variable created
       earlier.
    4. Add a parameter `@Advice.Return final HttpResponse response`. This is the `HttpResponse` returned by the
       instrumented target method (in this case `execute()`). Note this is not the same as the return value
       of `methodEnter()`.`  `
    5. Add a parameter `@Advice.Thrown final Throwable throwable`. This makes available any exception thrown by the
       target `execute()` method.
    6. Use `scope.span() `to obtain the `AgentSpan` and decorate the span as needed.
    7. If the scope was just created (not inherited), close it.

```java
public static class GoogleHttpClientAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(
            @Advice.This HttpRequest request,
            @Advice.Local("inherited") boolean inheritedScope
    ) {
        AgentScope scope = activeScope();
        if (null != scope) {
            AgentSpan span = scope.span();
            if (HTTP_REQUEST == span.getOperationName()) {
                inheritedScope = true;
                return scope;
            }
        }
        return activateSpan(DECORATE.prepareSpan(startSpan(HTTP_REQUEST),
                request));
    }


    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
            @Advice.Enter AgentScope scope,
            @Advice.Local("inherited") boolean inheritedScope,
            @Advice.Return final HttpResponse response,
            @Advice.Thrown final Throwable throwable) {
        try {
            AgentSpan span = scope.span();
            DECORATE.onError(span, throwable);
            DECORATE.onResponse(span, response);
            DECORATE.beforeFinish(span);
            span.finish();
        } finally {
            if (!inheritedScope) {
                scope.close();
            }
        }
    }
}
```

### Using @AppliesOn for Target System Control (Advanced)

If your instrumentation needs to apply different advices for different target systems, use the `@AppliesOn` annotation. This annotation allows you to specify which target systems an advice class should run for, independent of the InstrumenterModule's target system.

For example, if your module extends `InstrumenterModule.Tracing` but you want one advice to only run for context tracking:

```java
import datadog.trace.agent.tooling.InstrumenterModule.TargetSystem;
import datadog.trace.agent.tooling.annotation.AppliesOn;

@AppliesOn(TargetSystem.CONTEXT_TRACKING)
public static class ContextTrackingAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void extractParent(
            @Advice.Argument(0) Request request,
            @Advice.Local("parentScope") ContextScope parentScope) {
        // This advice only runs when CONTEXT_TRACKING is enabled
        final Context parentContext = DECORATE.extract(request);
        parentScope = parentContext.attach();
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void closeScope(@Advice.Local("parentScope") ContextScope scope) {
        scope.close();
    }
}

// This advice runs when TRACING is enabled (inherited from the module)
public static class TracingAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) Request request) {
        // Tracing-specific logic
    }
}
```

Then apply both advices:

```java
public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
            named("service"),
            getClass().getName() + "$ContextTrackingAdvice",  // Only for CONTEXT_TRACKING
            getClass().getName() + "$TracingAdvice"           // Only for TRACING
    );
}
```

This approach is useful when:
- You want to separate context propagation logic from tracing logic
- Different target systems need different instrumentation behaviors
- You're migrating from a tracing-only instrumentation to support multiple target systems

See [how_instrumentations_work.md](./how_instrumentations_work.md#applieson-annotation) for more details.

## Debugging

Debuggers include helpful features like breakpoints, watches and stepping through code. Unfortunately those features are
not available in Advice code during development of a Java agent. You’ll need to add `println()` statements and rebuild
the tracer JAR to test/debug in a traced client application.  `println()` is used instead of log statements because the
logger may not be initialized yet. Debugging should work as usual in helper methods that are called from advice code.

By default, advice code is inlined into instrumented code. In that case breakpoints can not be set in the advice code.
But when a method is annotated like this:

`@Advice.OnMethodExit(inline = false)`

or

`@Advice.OnMethodEnter(inline = false)`

the advice bytecode is not copied and the advice is invoked like a common Java method call, making it work like a helper
class. Debugging information is copied from the advice method into the instrumented method and debugging is possible.

It is not possible to use `inline=false` for all advice code. For example, when modifying argument
values, `@Argument(value = 0, readOnly = false)` is impossible since the advice is now a regular method invocation which
cannot be modified.

It is important to remove `inline=false` after debugging is finished for performance reasons.

(
see [inline](https://javadoc.io/static/net.bytebuddy/byte-buddy/1.14.10/net/bytebuddy/asm/Advice.OnMethodExit.html#inline--))

## Building

Configure your environment as discussed
in [CONTRIBUTING.md](../CONTRIBUTING.md). Make sure you have installed
the necessary JDK versions and set all environment variables as described there.

If you need to clean all results from a previous build:

```shell
./gradlew -p buildSrc clean
```

Build your new tracer jar:

```shell
./gradlew shadowJar
```

You will find the compiled SNAPSHOT jar here for example:

```shell
./dd-java-agent/build/libs/dd-java-agent-1.25.0-SNAPSHOT.jar
```

You can confirm your new integration is included in the jar:

```shell
java -jar dd-java-agent.jar --list-integrations
```

If Gradle is behaving badly you might try:

```
./gradlew --stop ; ./gradlew clean assemble
```

## Verifying Instrumentations

There are four verification strategies, three of which are mandatory.

- [Muzzle directives](./how_instrumentations_work.md#muzzle) (Required)
- [Instrumentation Tests](./how_instrumentations_work.md#instrumentation-tests) (Required)
- [Latest Dependency Tests](./how_instrumentations_work.md#latest-dependency-tests) (Required)
- [Smoke tests](./how_instrumentations_work.md#smoke-tests) (Not required)

All integrations must include sufficient test coverage. This HTTP client integration will include
a [standard HTTP test class](../dd-java-agent/instrumentation/google-http-client/src/test/groovy/GoogleHttpClientTest.groovy)
and
an [async HTTP test class](../dd-java-agent/instrumentation/google-http-client/src/test/groovy/GoogleHttpClientAsyncTest.groovy).
Both test classes inherit
from [HttpClientTest](../dd-java-agent/testing/src/main/groovy/datadog/trace/agent/test/base/HttpClientTest.groovy)
which provides a testing framework used by many HTTP client integrations. (
see [Testing](./how_instrumentations_work.md#testing))

## Running Tests

You can run only the tests applicable for this instrumentation:

```shell
./gradlew :dd-java-agent:instrumentation:google-http-client:test
```
