package datadog.trace.instrumentation.testng.execution;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.execution.TestExecutionTracker;
import datadog.trace.instrumentation.testng.TestEventsHandlerHolder;
import datadog.trace.instrumentation.testng.TestNGUtils;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import org.testng.IRetryAnalyzer;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;

public class RetryAnalyzer implements IRetryAnalyzer {

  private static final Map<ITestNGMethod, Map<TestIdentifier, RetryAnalyzer>> FRAMEWORK_TRACKERS =
      Collections.synchronizedMap(
          new WeakHashMap<ITestNGMethod, Map<TestIdentifier, RetryAnalyzer>>());
  private volatile TestExecutionPolicy executionPolicy;

  public static RetryAnalyzer forFrameworkMethod(ITestNGMethod method, TestIdentifier test) {
    synchronized (FRAMEWORK_TRACKERS) {
      Map<TestIdentifier, RetryAnalyzer> trackers = FRAMEWORK_TRACKERS.get(method);
      if (trackers == null) {
        trackers = new HashMap<>();
        FRAMEWORK_TRACKERS.put(method, trackers);
      }
      RetryAnalyzer tracker = trackers.get(test);
      if (tracker == null) {
        tracker = new RetryAnalyzer();
        trackers.put(test, tracker);
      }
      return tracker;
    }
  }

  private static final String SUPPRESS_FAILURES_ATTRIBUTE =
      RetryAnalyzer.class.getName() + ".suppressFailures";

  public void createExecutionPolicy(ITestResult result) {
    if (executionPolicy == null) {
      synchronized (this) {
        if (executionPolicy == null) {
          TestIdentifier testIdentifier = TestNGUtils.toTestIdentifier(result);
          TestSourceData testSourceData = TestNGUtils.toTestSourceData(result);
          Collection<String> testTags = TestNGUtils.getGroups(result);
          executionPolicy =
              TestEventsHandlerHolder.TEST_EVENTS_HANDLER.executionPolicy(
                  testIdentifier, testSourceData, testTags);
        }
      }
    }
  }

  @Override
  public boolean retry(ITestResult result) {
    if (TestEventsHandlerHolder.TEST_EVENTS_HANDLER == null) {
      return false;
    }
    createExecutionPolicy(result);
    return executionPolicy.applicable();
  }

  public void setSuppressFailures(ITestResult result) {
    createExecutionPolicy(result);
    result.setAttribute(SUPPRESS_FAILURES_ATTRIBUTE, executionPolicy.suppressFailures());
  }

  public boolean shouldPropagateFailure() {
    return executionPolicy != null && executionPolicy.propagateFailure();
  }

  public boolean getAndResetSuppressFailures(ITestResult result) {
    return Boolean.TRUE.equals(result.removeAttribute(SUPPRESS_FAILURES_ATTRIBUTE));
  }

  public TestExecutionTracker getExecutionTracker() {
    return executionPolicy;
  }
}
