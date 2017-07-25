# Datadog Java Agent for APM

*Minimal Java version required: 1.7*

This is a Java Agent to instrument Java applications using the Datadog Tracer.

### Disabling instrumentations

If for some reason you need to disable an instrumentation you should uncomment the `disabledInstrumentations: ` attribute in the configuration and provide a list as illustrated below:

```yaml
...

# Disable a few instrumentations
disabledInstrumentations: ["opentracing-apache-httpclient", "opentracing-mongo-driver", "opentracing-web-servlet-filter"]

```
The list of values that can be disabled are the top level keys found [here](src/main/resources/dd-trace-supported-framework.yaml)

### 

## Custom instrumentation

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

### Enabling custom tracing

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



