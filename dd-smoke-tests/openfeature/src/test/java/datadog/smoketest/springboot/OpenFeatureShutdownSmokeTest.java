package datadog.smoketest.springboot;

import datadog.smoketest.SmokeServerApp;
import org.junit.jupiter.api.extension.RegisterExtension;

/** The tracing shutdown hook flushes the SDK pending events. */
class OpenFeatureShutdownSmokeTest extends AbstractOpenFeatureShutdownSmokeTest {
  @RegisterExtension static final SmokeServerApp APP = shutdownApp("openfeature-shutdown", true);

  @Override
  SmokeServerApp app() {
    return APP;
  }
}
