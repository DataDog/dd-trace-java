package datadog.trace.instrumentation.testng7.order;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.events.TestEventsHandler;
import datadog.trace.api.civisibility.events.TestSuiteDescriptor;
import datadog.trace.instrumentation.testng.TestNGUtils;
import java.util.Comparator;
import java.util.List;
import org.testng.IMethodInstance;
import org.testng.IMethodInterceptor;
import org.testng.ITestContext;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;
import org.testng.internal.TestResult;

public class FailFastOrderInterceptor implements IMethodInterceptor {

  private final TestEventsHandler<TestSuiteDescriptor, ITestResult> testEventsHandler;
  private final Comparator<IMethodInstance> knownAndStableTestsComparator;

  public FailFastOrderInterceptor(
      TestEventsHandler<TestSuiteDescriptor, ITestResult> testEventsHandler) {
    this.testEventsHandler = testEventsHandler;
    this.knownAndStableTestsComparator = Comparator.comparing(this::isKnownAndStable);
  }

  private boolean isKnownAndStable(IMethodInstance methodInstance) {
    ITestNGMethod method = methodInstance.getMethod();
    if (method == null) {
      return true;
    }
    ITestResult testResult = TestResult.newTestResultFor(method);
    TestIdentifier testIdentifier = TestNGUtils.toTestIdentifier(testResult);
    return !testEventsHandler.isNew(testIdentifier) && !testEventsHandler.isFlaky(testIdentifier);
  }

  @Override
  public List<IMethodInstance> intercept(List<IMethodInstance> list, ITestContext iTestContext) {
    list.sort(knownAndStableTestsComparator);
    return list;
  }
}
