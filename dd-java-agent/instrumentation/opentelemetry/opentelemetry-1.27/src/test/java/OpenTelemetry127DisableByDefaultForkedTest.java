// Forked test: runs in an isolated JVM with no opentelemetry-logs config override,
// verifying that the instrumentation is disabled by default.
// GlobalOpenTelemetry holds static state that must reset between variants.
class OpenTelemetry127DisableByDefaultForkedTest extends OpenTelemetry127ActivationTest {

  @Override
  boolean shouldBeInjected() {
    return false;
  }
}
