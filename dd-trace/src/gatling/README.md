# Benchmarking

This part of the project is an attempt to benchmark the 
overhead of using the tracer. 

It's still work in progress, but the first results show a minimal 
overhead.

So, this directory is a collection of various Gatling scenario in order
to bench different configurations.

## Setup

1. Get the latest version of Gatling: http://gatling.io/download/
2. Then run the loader using: `bin/gatling.sh -sf /path/to/src/gatling/package-name`


### Spring boot specific setup

**With the tracer activated.**

No special needs here, just start the web server with the `-jaavagent:` option set.
Then run 
```
cd dd-trace-java/dd-trace/src/gatling/
$GATLING_HOME/bin/gatling.sh -rf ./reports -sf ./scala```
```
You should expect a similar output:
```
---- Global Information --------------------------------------------------------
> request count                                     464842 (OK=464842 KO=0     )
> min response time                                      0 (OK=0      KO=-     )
> max response time                                   1596 (OK=1596   KO=-     )
> mean response time                                     4 (OK=4      KO=-     )
> std deviation                                         12 (OK=12     KO=-     )
> response time 50th percentile                          2 (OK=2      KO=-     )
> response time 75th percentile                          4 (OK=4      KO=-     )
> response time 95th percentile                          9 (OK=9      KO=-     )
> response time 99th percentile                         21 (OK=21     KO=-     )
> mean requests/sec                                1544.326 (OK=1544.326 KO=-     )
---- Response Time Distribution ------------------------------------------------
> t < 800 ms                                        464822 (100%)
> 800 ms < t < 1200 ms                                   9 (  0%)
> t > 1200 ms                                           11 (  0%)
> failed                                                 0 (  0%)
================================================================================
```

**Whitout the tracer**

In order to bench the Spring Boot example, you have to change the DB driver.
Edit the `application.properties` and replace 

```
spring.datasource.driver-class-name=io.opentracing.contrib.jdbc.TracingDriver
spring.datasource.url=jdbc:tracing:h2:mem:spring-test
``` 
by

```
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.url=jdbc:h2:mem:spring-test
```

Then restart the application without the `javaagent` set.

