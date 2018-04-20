## StackState Java Tracer examples

The goal of this repository is to offer you some examples about how to instrument your code
using the OpenTracing API and the STS Tracer.

![](https://datadog-live.imgix.net/img/datadog_logo_share_tt.png)

Here are the examples
* [Dropwizard (Jax-Rs) + Mongo database + HTTP Client](dropwizard-mongo-client/README.md)
* [Spring-boot + MySQL JDBC database + Redis (Jedis client)](spring-boot-jdbc-redis/README.md)
* [Instrumenting using a Java Agent](javaagent/README.md)


## Prerequisites

In order to run the demos, you have to do something before:

* Get the latest lib of the STS-Tracer and push it to the lib directory
* Make sure you have a running StackState Agent on the local port 8126 (default one)
* In the StackState agent configuration, set APM to true (and restart it)
* Maven
