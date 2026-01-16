package datadog.trace.instrumentation.testng7.order;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
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
  private final Comparator<IMethodInstance> executionOrderComparator;

  public FailFastOrderInterceptor(
      TestEventsHandler<TestSuiteDescriptor, ITestResult> testEventsHandler) {
    this.testEventsHandler = testEventsHandler;
    this.executionOrderComparator = Comparator.comparing(this::executionPriority).reversed();
  }

  private int executionPriority(IMethodInstance methodInstance) {
    ITestNGMethod method = methodInstance.getMethod();
    if (method == null) {
      // not expected to happen, just a safeguard
      return 0;
    }
    ITestResult testResult = TestResult.newTestResultFor(method);
    TestIdentifier testIdentifier = TestNGUtils.toTestIdentifier(testResult);
    TestSourceData testSourceData = TestNGUtils.toTestSourceData(testResult);
    return testEventsHandler.executionPriority(testIdentifier, testSourceData);
  }

  @Override
  public List<IMethodInstance> intercept(List<IMethodInstance> list, ITestContext iTestContext) {
    list.sort(executionOrderComparator);
    return list;
  }
}
