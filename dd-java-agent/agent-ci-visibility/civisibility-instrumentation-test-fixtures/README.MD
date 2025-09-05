# civisibility-instrumentation-test-fixtures

This project contains common logic used in CI Visibility instrumentation tests.

The reason it is defined as a separate project rather than using testFixtures plugin in the parent module is because it
transitively pulls in :dd-java-agent:instrumentation-testing.
The latter contains datadog.trace.agent.test.BootstrapClasspathSetupListener that, when present on the classpath,
patches bootstrap CL adding some tracer classes to it.
This is needed for the instrumentation tests, but may break regular unit tests.
If this project used testFixtures, the listener would end up on the classpath of the parent project's unit tests, breaking them.
