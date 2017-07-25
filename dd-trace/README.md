# Datadog OpenTracing Tracer

## How to instrument your application?

### <a name="api"></a>Custom instrumentations using OpenTracing API

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

`DDTracerFactory` looks for a `dd-trace.yaml` file in the classpath. 

Finally, do not forget to add the corresponding dependencies to your project.

Maven:
```xml
        <!-- OpenTracing API -->
        <dependency>
            <groupId>io.opentracing</groupId>
            <artifactId>opentracing-api</artifactId>
            <version>${opentracing.version}</version>
        </dependency>
        
        <!-- Datadog Tracer (only needed if you do not use dd-java-agent) -->
        <dependency>
            <groupId>com.datadoghq</groupId>
            <artifactId>dd-trace</artifactId>
            <version>${dd-trace-java.version}</version>
        </dependency>

```

Gradle:
```groovy
        compile group: 'io.opentracing', name: 'opentracing-api', version: "${opentracing.version}"
        compile group: 'com.datadoghq', name: 'dd-trace', version: "${dd-trace-java.version}"
```

### <a name="annotation"></a>Adding Trace annotations to your methods

An easy way to improve visibility to your application is by adding the `@Trace` annotation on the methods you want to instrument.
This is equivelent to the `method0` example from the [api](#api) section.

```java
class InstrumentedClass {

    @Trace(operationName = "operation-name-1")
    void method1() {

        //Do some thing here ...
        Thread.sleep(1_000);
    }	
    
    @Trace(operationName = "operation-name-2")
    void method2() {
        
        // You can get the current span and add tag as follow
        Span current = io.opentracing.util.GlobalTracer.get().activeSpan();
        new io.opentracing.tag.StringTag("service-name").set(current, "new-service-name");

        //Do some thing here ...
        Thread.sleep(1_000);
    }	
}
```

In order to use annotations, the only required dependency is `dd-trace-annotations`.
Maven:
```xml
        <!-- Datadog annotations -->
        <dependency>
            <groupId>com.datadoghq</groupId>
            <artifactId>dd-trace-annotations</artifactId>
            <version>${dd-trace-java.version}</version>
        </dependency>
```
Gradle:
```groovy
        compile group: 'com.datadoghq', name: 'dd-trace-annotations', version: "${dd-trace-java.version}"
```

**The annotations are resolved at the runtime by the Datadog Java agent. If you want to use the annotations,
so you must run the Datadog Java Agent.**
