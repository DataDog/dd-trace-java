import datadog.trace.instrumentation.testng.TestNGTest

class TestNG6Test extends TestNGTest {
  @Override
  protected String version() {
    return "6"
  }
}
