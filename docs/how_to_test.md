# How to Test

## The Different Types of Tests

The project leverages different types of test:

1. The most common ones are **unit tests**.  
They are intended to test a single isolated feature, and rely on [JUnit 5 framework](https://junit.org/junit5/docs/current/user-guide/) or [Spock 2 framework](https://spockframework.org/spock/docs/).
JUnit framework is recommended for most unit tests for its simplicity and performance reasons.
Spock framework provides an alternative for more complex test scenarios, or tests that requires Groovy Script to access data outside their scope limitation (eg private fields).

2. A variant of unit tests are **instrumented tests**.  
Their purpose is similar to the unit tests but the tested code is instrumented by the java agent (`:dd-trace-java:java-agent`) while running. They extend the Spock specification `datadog.trace.agent.test.AgentTestRunner` which allows to test produced traces and metrics. 

3. The third type of tests are **integration tests**.  
They test features that requires a more complex environment setup.
In order to build such enviroments, integration tests use Testcontainers to setup the services needed to run the tests.

4. The last type of test are **smoke tests**.  
They are dedicated to test the java agent (`:dd-java-agent`) behavior against demo applications to prevent any regression. All smoke tests are located into the `:dd-smoke-tests` module. 

> [!TIP]
> Most of the instrumented tests and integration tests are instrumentation tests.

### Forked Tests

Independently of the type of test, test can be run in another (forked) JVM than the one running Gradle.
This behavior is implicit when the test class name is suffixed by `ForkedTest` (eg `SomeFeatureForkedTest`). This mechanism exists to make sure either java agent state or static data are reset between test runs.

### Flaky Tests

If a test runs unreliably, or doen't have a fully deterministic behavior, this will lead into recurrent unexpected errors in continuous integration.
In order to identify such tests and avoid the continuous integration to fail, they are marked as _flaky_ and must be annotated with the `@Flaky` annotation.

## Running Tests

You can run the whole project test suite using `./gradlew test` but expect it to take a certain time.
Instead, you can run test for a specific module (ex. `:dd-java-agent:instrumentation:opentelemetry:opentelemetry-1.4`) using the test command for this module only: `./gradlew :dd-java-agent:instrumentation:opentelemetry:opentelemetry-1.4:test`.

> [!TIP]
> Flaky tests can be disabled by setting the Gradle property `skipInstTests` (ex. `./gradlew -PskipFlakyTests <task>`).

### Running tests on another JVM

To run tests on a different JVM than the one used for doing the build, you need two things:

1) An environment variable pointing to the JVM to use on the form `JAVA_[JDKNAME]_HOME`,
   e.g. `JAVA_ZULU15_HOME`, `JAVA_GRAALVM17_HOME`

2) A command line option to the gradle task on the form `-PtestJvm=[JDKNAME]`,
   e.g. `-PtestJvm=ZULU15`, `-PtestJvm=GRAALVM17`

> [!NOTE]
> Please note that the JDK name needs to end with the JDK version, e.g. `11`, `ZULU15`, `ORACLE8`, `GRAALVM17`, etc.

### The APM test agent

The APM test agent emulates the APM endpoints of the Datadog Agent.
The APM Test Agent container runs alongside Java tracer Instrumentation Tests in CI,
handling all traces during test runs and performing a number of `Trace Checks`.
Trace Check results are returned within the `Get APM Test Agent Trace Check Results` step for all instrumentation test jobs.
Check [trace invariant checks](https://github.com/DataDog/dd-apm-test-agent#trace-invariant-checks) for more informations.

The APM Test Agent also emits helpful logging, including logging received traces' headers, spans, errors encountered,
ands information on trace checks being performed. 
Logs can be viewed in CircleCI within the Test-Agent container step for all instrumentation test suites, ie: `z_test_8_inst` job.
Read more about [the APM Test Agent](https://github.com/datadog/dd-apm-test-agent#readme).
