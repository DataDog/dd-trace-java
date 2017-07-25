## What is Datadog APM?

Datadog APM traces the path of each request through your application stack, recording the latency of each step along the way. It sends all trace data to Datadog, where you can easily identify which services or calls are most responsible for slow response times.
 
This repository contains the resources you need to trace Java applications. Two quick notes up front:

- **Datadog Java APM is currently in Beta**
- Datadog Java APM can only trace applications running Java 1.7 or later

## Getting Started

You need three things to trace Java applications:

**[Datadog Tracer]()**: an OpenTracing-compatible library that lets you trace your Java code using annotations and/or more flexible instrumentation
**[Datadog Java Agent](https://github.com/DataDog/dd-trace-java/tree/master/dd-java-agent)**: a Java Agent that, when passed to your application:
  1. Lets you instrument your Java code using the Datadog Tracer
  2. Automatically traces many Java frameworks, servers, and databases via libraries from [opentracing-contrib](https://github.com/opentracing-contrib)
  3. Sends all trace data from **1** and **2** to the Datadog APM Agent
**[Datadog APM Agent](https://github.com/DataDog/datadog-trace-agent)**: a (non-Java) service that runs on all your hosts, accepting trace data from the Datadog Java Agent and sending it to Datadog

Let's address these in reverse order.

### Datadog APM Agent

[Install the Datadog Agent](https://app.datadoghq.com/account/settings#agent) on all your hosts and enable the APM Agent. Refer to special instructions for [macOS](https://github.com/DataDog/datadog-trace-agent#run-on-osx) and [Docker](https://github.com/DataDog/docker-dd-agent#tracing--apm) if you're using either.

### Instrument your application

To dynamically apply instrumentation you simply have to declare the provided `jar` file in your JVM arguments as a valid `-javaagent:`.

- So first download the `jar` file from the main repository.

*NOTE:* While in beta, the latest version is best found on the [Snapshot Repo](https://oss.jfrog.org/artifactory/oss-snapshot-local/com/datadoghq/). 

```
# download the latest published version:
wget -O dd-java-agent.jar 'https://search.maven.org/remote_content?g=com.datadoghq&a=dd-java-agent&v=LATEST'
```

- Then add the following JVM argument when launching your application (in your IDE, your maven or gradle application script, or your `java -jar` command):

```
-javaagent:/path/to/the/dd-java-agent.jar
```

You should then see traces on [Datadog APM](https://app.datadoghq.com/apm/search).
For troubleshooting, look for the `trace-agent.log` along side the other [agent logs](https://help.datadoghq.com/hc/en-us/articles/203037159-Log-Locations).

## Configuration

Configuration is done through a default `dd-trace.yaml` file as a resource in the classpath.
You can also override it by adding the file path as a system property when launching the JVM: `-Ddd.trace.configurationFile`.

```yaml
# Main service name for the app
defaultServiceName: java-app

# The writer to use.
# Could be: LoggingWritter or DDAgentWriter (default)
writer:
  # LoggingWriter: Spans are logged using the application configuration
  # DDAgentWriter: Spans are forwarding to a Datadog trace Agent
  #  - Param 'host': the hostname where the DD trace Agent is running (default: localhost)
  #  - Param 'port': the port to reach the DD trace Agent (default: 8126)
  type: DDAgentWriter
  host: localhost
  port: 8126

# The sampler to use.
# Could be: AllSampler (default) or RateSampler
sampler:
  # AllSampler: all spans are reported to the writer
  # RateSample: only a portion of spans are reported to the writer
  #  - Param 'rate': the portion of spans to keep
  type: AllSampler
  # Skip some traces if the root span tag values matches some regexp patterns
  # skipTagsPatterns: {"http.url": ".*/demo/add.*"}
  
# Enable custom tracing (Custom annotations for now)
# enableCustomAnnotationTracingOver: ["io","org","com"]

# Disable some instrumentations
# disabledInstrumentations: ["opentracing-apache-httpclient", "opentracing-mongo-driver", "opentracing-web-servlet-filter"]
```

## Automatic Tracing

The Datadog Java Agent automatically traces the following frameworks, servers, and databases using libraries from [opentracing-contrib](https://github.com/opentracing-contrib):

### Frameworks

| Framework        | Versions           | Comments  |
| ------------- |:-------------:| ----- |
| [OkHTTP](https://github.com/opentracing-contrib/java-okhttp) | 3.x | HTTP client calls with [cross-process](http://opentracing.io/documentation/pages/api/cross-process-tracing.html) headers |
| [Apache HTTP Client](https://github.com/opentracing-contrib/java-apache-httpclient) | 4.3 + | HTTP client calls with [cross-process](http://opentracing.io/documentation/pages/api/cross-process-tracing.html) headers|
| [AWS SDK](https://github.com/opentracing-contrib/java-aws-sdk) | 1.11.119+ | Trace all client calls to any AWS service |
| [Web Servlet Filters](https://github.com/opentracing-contrib/java-web-servlet-filter) | Depends on web server | See [Servers](#servers) |
| [JMS 2](https://github.com/opentracing-contrib/java-jms) | 2.x | Trace calls to message brokers; distributed trace propagation not yet supported |

### Servers

| Server | Versions | Comments |
| ------------- |:-------------:| -----|
| Jetty | 8.x, 9.x | HTTP client calls with [cross-process](http://opentracing.io/documentation/pages/api/cross-process-tracing.html) headers |
| Tomcat | 8.0.x, 8.5.x & 9.x | HTTP client calls with [cross-process](http://opentracing.io/documentation/pages/api/cross-process-tracing.html) headers |

Modern web application frameworks that use these servers—Dropwizard and Spring Boot, for example—are automatically traced as well.

### Databases
| DB        | Versions           | Comments  |
| ------------- |:-------------:| ----- |
| Spring JDBC| 4.x | **NOT enabled automatically** – install [opentracing-contrib/java-jdbc]((https://github.com/opentracing-contrib/java-jdbc) to enable |
| Hibernate | 5.x | **NOT enabled automatically** – install [opentracing-contrib/java-jdbc]((https://github.com/opentracing-contrib/java-jdbc) to enable |
| [MongoDB](https://github.com/opentracing-contrib/java-mongo-driver) | 3.x | Intercepts all the calls from the MongoDB client |
| [Cassandra](https://github.com/opentracing-contrib/java-cassandra-driver) | 3.2.x | Intercepts all the calls from the Cassandra client |

If you want to disable tracing for any of these frameworks, servers, or databases, list the relevant libraries in `disabledInstrumentations` within `dd-trace.yaml`:

```yaml
...

# Disable tracing on these
disabledInstrumentations: ["opentracing-apache-httpclient", "opentracing-mongo-driver", "opentracing-web-servlet-filter"]
```

See [this YAML file](src/main/resources/dd-trace-supported-framework.yaml) for the names of all supported libraries (i.e. the names you can list in `disabledInstrumentations`).

## Manual Tracing (Instrumentation)

### Add dependencies to your project

- Add the annotations jar as a dependency of your project

```xml
<dependency>
	<groupId>com.datadoghq</groupId>
	<artifactId>dd-trace-annotations</artifactId>
	<version>{version}</version>
</dependency>
```
or
```gradle
compile group: 'com.datadoghq', name: 'dd-trace-annotations', version: {version}
```

- Enable custom tracing in the `dd-trace.yaml` config file by setting the packages you would like to scan as follows `enableCustomAnnotationTracingOver: ["io","org","com"]`.

### Custom Tracing

### The `@Trace` annotation

By adding the `@Trace` annotation to a method the `dd-java-agent` automatically measures the execution time.

```java
@Trace
public void myMethod() throws InterruptedException{
		...
}
```

By default, the operation name attached to the spawn span will be the name of the method and no meta tags will be attached.

You can use the `operationName` customize your trace:

```java
@Trace(operationName="Before DB")
public void myMethod() throws InterruptedException{
	....
}
``` 




### Further Reading/Links/Examples

- [Browse examples](dd-trace-examples) - See how to instrument legacy projects based on the most used technologies.
- [Instrument with OpenTracing](https://github.com/opentracing/opentracing-java) - Datadog embraces the OpenTracing initiative. So feel free to use the Trace Java API to customize your instrumentation.
- Improve your APM experience for apps running on docker by enabling the [Docker Agent](https://app.datadoghq.com/apm/docs/tutorials/docker)
- Datadog's APM [Terminology](https://app.datadoghq.com/apm/docs/tutorials/terminology)
- [FAQ](https://app.datadoghq.com/apm/docs/tutorials/faq)

### Get in touch
 
If you have questions or feedback, email us at tracehelp@datadoghq.com or chat with us in the datadoghq slack channel #apm-java.
