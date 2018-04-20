## Spring-boot 

This project provides a simple API using [Spring Boot][1]. The framework is supported and auto-instrumentation is used
to trace the endpoints.

[1]: https://projects.spring.io/spring-boot/
 
### Run the demo

#### Prerequisites

Be sure to build the project so that the latest version of ``sts-trace-java`` components are used. You can build
all libraries and examples launching from the ``sts-trace-java`` root folder:
```bash
./gradlew clean shadowJar bootRepackage
```

Then you can launch the StackState agent and a Redis instance as follows:
```bash
cd examples/spring-boot-jdbc-redis
STS_API_KEY=<your_stackstate_api_key> docker-compose up -d
```

A valid ``STS_API_KEY`` is required to post collected traces to the StackState backend.

#### Run the application

To launch the application, just:
```bash
./gradlew bootRun
```

*Note: The ``bootRun`` Gradle command appends automatically the ``-javaagent`` argument, so that you don't need to specify
the path of the Java Agent. Gradle executes the ``:examples:spring-boot-jdbc-redis:bootRun`` task until you
stop it.*

Or as an executable jar:
```bash
java -javaagent:../../sts-java-agent/build/libs/sts-java-agent-{version}.jar -Dsts.service.name=spring-boot-jdbc-redis -jar build/libs/spring-boot-jdbc-redis-demo.jar
```

### Generate traces

Once the Gradle task is running. Go to the following urls:

* [http://localhost:8080/user/add?name=foo&email=bar](http://localhost:8080/user/add?name=foo&email=bar)
* [http://localhost:8080/user/all](http://localhost:8080/user/all)
* [http://localhost:8080/user/all](http://localhost:8080/user/random)

Then get back to StackState and wait a bit to see a trace coming.

#### Auto-instrumentation with the `sts-trace-agent`

The instrumentation is entirely done by the stackstate agent which embed a set of rules that automatically recognizes &
instruments:

- The java servlet filters
- The JDBC driver
- The Jedis Redis client

The Java Agent embeds the [OpenTracing Java Agent](https://github.com/opentracing-contrib/java-agent).
