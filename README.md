## What is Datadog APM?

Datadog APM traces the path of each request through your application stack, recording the latency of each step along the way. It sends all tracing data to Datadog, where you can easily identify which services or calls are slowing down your application the most.
 
This repository contains the resources you need to trace Java applications. Two quick notes up front:

- **Datadog Java APM is currently in Beta**
- Datadog Java APM can only trace applications running Java 1.7 or later

## Getting Started

You need three things to trace Java applications:

**[Datadog Tracer](https://github.com/DataDog/dd-trace-java/tree/master/dd-trace)**: an OpenTracing-compatible library that lets you trace your Java code using annotations and/or more flexible instrumentation

**[Datadog Java Agent](https://github.com/DataDog/dd-trace-java/tree/master/dd-java-agent)**: a Java Agent that, when passed to your application:
1. Lets you instrument your Java code using the Datadog Tracer library
2. Automatically traces many Java frameworks, servers, and databases via libraries from [opentracing-contrib](https://github.com/opentracing-contrib)
3. Sends all trace data from 1 and 2 to the Datadog APM Agent

**[Datadog APM Agent](https://github.com/DataDog/datadog-trace-agent)**: a (non-Java) service that runs on all your hosts, accepting trace data from the Datadog Java Agent and sending it to Datadog

Let's address these in reverse order.

### Datadog APM Agent

[Install the Datadog Agent](https://app.datadoghq.com/account/settings#agent) on all your hosts and enable the APM Agent. See the special instructions for [macOS](https://github.com/DataDog/datadog-trace-agent#run-on-osx) and [Docker](https://github.com/DataDog/docker-dd-agent#tracing--apm) if you're using either.

### Datadog Java Agent

#### Download the latest release

```
$ wget -O dd-java-agent.jar 'https://oss.jfrog.org/artifactory/oss-snapshot-local/com/datadoghq/dd-java-agent/0.1.2-SNAPSHOT/dd-java-agent-0.1.2-20170725.120841-20.jar'
```

#### Configure it

Create a file `dd-trace.yaml` anywhere in your application's classpath (or provide the path to it via `-Ddd.trace.configurationFile` when starting the application):

```yaml
# Main service name for the app
defaultServiceName: my-java-app

writer:
  type: DDAgentWriter # send traces to Datadog Trace Agent; only other option is LoggingWriter
  host: localhost     # host/IP where Datadog Trace Agent listens
  port: 8126          # port where Datadog Trace Agent listens

sampler:
  type: AllSampler # Collect 100% of traces; only other option is RateSample
# rate: 0.5        # if using type: RateSample, uncomment to collect only 50% of traces
```

#### Use it

Add the following JVM argument when starting your application—in your IDE, your Maven or gradle application script, or your `java -jar` command:

```
-javaagent:/path/to/the/dd-java-agent.jar
```

## Automatic Tracing

The Datadog Java Agent automatically traces requests to many frameworks, application servers, and databases using various libraries from [opentracing-contrib](https://github.com/opentracing-contrib). In most cases you don't need to configure anything else. The exception: any database library that requires JDBC (see comments in the Databases table below).

### Frameworks

| Framework        | Versions           | Comments  |
| ------------- |:-------------:| ----- |
| [OkHTTP](https://github.com/opentracing-contrib/java-okhttp) | 3.x | HTTP client calls with [cross-process](http://opentracing.io/documentation/pages/api/cross-process-tracing.html) headers |
| [Apache HTTP Client](https://github.com/opentracing-contrib/java-apache-httpclient) | 4.3 + | HTTP client calls with [cross-process](http://opentracing.io/documentation/pages/api/cross-process-tracing.html) headers|
| [AWS SDK](https://github.com/opentracing-contrib/java-aws-sdk) | 1.11.119+ | Trace all client calls to any AWS service |
| [Web Servlet Filters](https://github.com/opentracing-contrib/java-web-servlet-filter) | Depends on web server | See [Servers](#servers) |
| [JMS 2](https://github.com/opentracing-contrib/java-jms) | 2.x | Trace calls to message brokers; distributed trace propagation not yet supported |

### Application Servers

| Server | Versions | Comments |
| ------------- |:-------------:| -----|
| Jetty | 8.x, 9.x | HTTP client calls with [cross-process](http://opentracing.io/documentation/pages/api/cross-process-tracing.html) headers |
| Tomcat | 8.0.x, 8.5.x & 9.x | HTTP client calls with [cross-process](http://opentracing.io/documentation/pages/api/cross-process-tracing.html) headers |

Requests to any web frameworks that use these application servers—Dropwizard and Spring Boot, for example—are automatically traced as well.

### Databases

| Database      | Versions           | Comments  |
| ------------- |:-------------:| ----- |
| Spring JDBC| 4.x | **NOT enabled automatically** – install [opentracing-contrib/java-jdbc](https://github.com/opentracing-contrib/java-jdbc) |
| Hibernate | 5.x | **NOT enabled automatically** – install [opentracing-contrib/java-jdbc](https://github.com/opentracing-contrib/java-jdbc) |
| [MongoDB](https://github.com/opentracing-contrib/java-mongo-driver) | 3.x | Intercepts all the calls from the MongoDB client |
| [Cassandra](https://github.com/opentracing-contrib/java-cassandra-driver) | 3.2.x | Intercepts all the calls from the Cassandra client |

---

To disable tracing for any of these libraries, list them in `disabledInstrumentations` within `dd-trace.yaml`:

```yaml
...

# Disable tracing on these
disabledInstrumentations: ["opentracing-apache-httpclient", "opentracing-mongo-driver", "opentracing-web-servlet-filter"]
```

See [this YAML file](src/main/resources/dd-trace-supported-framework.yaml) for the proper names of all supported libraries (i.e. the names as you must list them in `disabledInstrumentations`).

## Instrument Your Code

### Custom Tracing

#### Dependencies

For Maven, add this to pom.xml:
```xml
        <!-- OpenTracing API -->
        <dependency>
            <groupId>io.opentracing</groupId>
            <artifactId>io.opentracing:opentracing-api</artifactId>
            <version>0.30.0</version>
        </dependency>

        <!-- OpenTracing Util -->
        <dependency>
            <groupId>io.opentracing</groupId>
            <artifactId>io.opentracing:opentracing-util</artifactId>
            <version>0.30.0</version>
        </dependency>
        
        <!-- Datadog Tracer (only needed if you do not use dd-java-agent) -->
        <dependency>
            <groupId>com.datadoghq</groupId>
            <artifactId>dd-trace</artifactId>
            <version>${dd-trace-java.version}</version>
        </dependency>
```

For gradle, add:

```groovy
        compile group: 'io.opentracing', name: 'opentracing-api', version: "0.30.0"
        compile group: 'io.opentracing', name: 'opentracing-util', version: "0.30.0"
        compile group: 'com.datadoghq', name: 'dd-trace', version: "${dd-trace-java.version}"
```

#### Examples

Rather than referencing classes directly from `dd-trace` (other than registering `DDTracer`), we strongly suggest using the [OpenTracing API](https://github.com/opentracing/opentracing-java).
[Additional documentation on the api](docs/opentracing-api.md) is also available. 

Let's look at a simple example.

```java
class InstrumentedClass {
    
    void method0() {
        // Retrieve the tracer using the resolver provided
        // Make sure you have :
        //    1. added the agent to the jvm (-javaagent;/path/to/agent.jar)
        //    2. a dd-trace.yaml file in your resources directory
        Tracer tracer = io.opentracing.util.GlobalTracer.get();
        
        Span span = tracer.buildSpan("operation-name").startActive();
        new io.opentracing.tag.StringTag("service-name").set(span, "new-service-name"); 
        
        
        //Do some thing here ...
        Thread.sleep(1_000);
        
        // Close the span, the trace will automatically reported to the writer configured
        span.finish();   
    }
}
``` 

The method above is now instrumented. As you can see, the tracer is retrieved from a global registry, called `GlobalTracer`.

The last thing you have to do is providing a configured tracer. This can be easily done by using the `TracerFactory` or manually
in the bootstrap method (like the `main`).

```java
public class Application {

    public static void main(String[] args) {
	
        // Init the tracer from the configuration file      
        Tracer tracer = DDTracerFactory.createFromConfigurationFile();
        io.opentracing.util.GlobalTracer.register(tracer);
        
        // OR
        // Init the tracer from the API
        Writer writer = new com.datadoghq.trace.writer.DDAgentWriter();
        Sampler sampler = new com.datadoghq.trace.sampling.AllSampler();
        Tracer tracer = new com.datadoghq.trace.DDTracer(writer, sampler);
        io.opentracing.util.GlobalTracer.register(tracer);
        
        // ...
    }
}
```

`DDTracerFactory` looks for `dd-trace.yaml` in the classpath. 

### The @Trace annotation

If you've passed the Datadog Java Agent to your application, you can add a `@Trace` annotation to any method to measure its run time. First, add the `dd-trace-annotations` dependency to your project.

For Maven, add this to pom.xml:

```xml
<dependency>
	<groupId>com.datadoghq</groupId>
	<artifactId>dd-trace-annotations</artifactId>
	<version>{version}</version>
</dependency>
```

For gradle, add:

```gradle
compile group: 'com.datadoghq', name: 'dd-trace-annotations', version: {version}
```

Then enable custom tracing in `dd-trace.yaml` by setting the packages you want to trace:

```yaml
enableCustomAnnotationTracingOver: ["io","org","com"]`.
```

Finally, add an annotation to some method in your code:

```java
@Trace
public void myMethod() throws InterruptedException{
		...
}
```

You can pass an `operationName` to tag the trace data as you want:

```java
@Trace(operationName="Before DB")
public void myMethod() throws InterruptedException{
	....
}
```

When you don't pass an `operationName`, the Java Agent sets it to the method name.

## Further Reading

- Browse the [example applications](dd-trace-examples) in this repository to see Java tracing in action
- Read [OpenTracing's documentation](https://github.com/opentracing/opentracing-java); feel free to use the Trace Java API to customize your instrumentation.
- Brush up on [Datadog APM Terminology](https://docs.datadoghq.com/tracing/terminology/)
- Read the [Datadog APM FAQ](https://docs.datadoghq.com/tracing/faq/)

## Get in touch
 
If you have questions or feedback, email us at tracehelp@datadoghq.com or chat with us in the datadoghq slack channel #apm-java.
