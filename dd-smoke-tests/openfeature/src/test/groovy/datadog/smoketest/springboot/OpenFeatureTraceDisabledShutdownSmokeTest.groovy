package datadog.smoketest.springboot

/**
 * Without tracing, the Feature Flagging system registers its own shutdown hook to flush the SDK
 * pending events.
 */
class OpenFeatureTraceDisabledShutdownSmokeTest extends AbstractOpenFeatureShutdownSmokeTest {

  @Override
  boolean tracing() {
    return false
  }
}
