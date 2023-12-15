import datadog.trace.instrumentation.testng.TestNGTest
import datadog.trace.instrumentation.testng.TracingListener

class TestNG7Test extends TestNGTest {
  @Override
  protected String version() {
    TracingListener.FRAMEWORK_VERSION >= "7.5" ? "latest" : "7"
  }
}
