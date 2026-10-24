package datadog.smoketest.springboot

/** The tracing shutdown hook flushes the SDK pending events. */
class OpenFeatureShutdownSmokeTest extends AbstractOpenFeatureShutdownSmokeTest {

  @Override
  boolean tracing() {
    return true
  }
}
