package datadog.trace.instrumentation.testng;

import org.testng.ISuite;
import org.testng.ISuiteListener;

/**
 * TestNGClassListener cannot implement ISuiteListener directly: if an instance implements both
 * ISuiteListener and ITestListener, it is registered twice (probably a bug in TestNG)
 */
public class TestNGSuiteListener implements ISuiteListener {

  private final TestNGClassListener delegate;

  public TestNGSuiteListener(TestNGClassListener delegate) {
    this.delegate = delegate;
  }

  @Override
  public void onStart(ISuite iSuite) {
    delegate.registerTestMethods(iSuite.getAllMethods());
  }

  @Override
  public void onFinish(ISuite iSuite) {
    // ignore
  }
}
