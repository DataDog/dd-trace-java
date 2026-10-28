package datadog.smoketest.springboot;

import datadog.smoketest.SmokeServerApp;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Without tracing, the Feature Flagging system registers its own shutdown hook to flush the SDK
 * pending events.
 */
class OpenFeatureTraceDisabledShutdownSmokeTest extends AbstractOpenFeatureShutdownSmokeTest {
  @RegisterExtension
  static final SmokeServerApp APP = shutdownApp("openfeature-trace-disabled-shutdown", false);

  @Override
  SmokeServerApp app() {
    return APP;
  }
}
