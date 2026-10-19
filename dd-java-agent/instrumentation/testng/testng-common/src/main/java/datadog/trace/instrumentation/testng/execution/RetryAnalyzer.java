package datadog.trace.instrumentation.testng.execution;

import datadog.trace.api.civisibility.config.TestExclusions;
import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.config.TestSourceData;
import datadog.trace.api.civisibility.execution.TestExecutionPolicy;
import datadog.trace.api.civisibility.execution.TestExecutionTracker;
import datadog.trace.instrumentation.testng.TestEventsHandlerHolder;
import datadog.trace.instrumentation.testng.TestNGUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;
import org.testng.annotations.Test;

public class RetryAnalyzer implements IRetryAnalyzer {

  private static final Logger LOGGER = LoggerFactory.getLogger(RetryAnalyzer.class);
  private volatile TestExecutionPolicy executionPolicy;
  private IRetryAnalyzer frameworkRetryAnalyzer;

  @SuppressFBWarnings(
      value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
      justification = "the field is confined to a single thread")
  private boolean suppressFailures;

  public void createExecutionPolicy(ITestResult result) {
    if (executionPolicy == null) {
      synchronized (this) {
        if (executionPolicy == null) {
          TestIdentifier testIdentifier = TestNGUtils.toTestIdentifier(result);
          TestSourceData testSourceData = TestNGUtils.toTestSourceData(result);
          Collection<String> testTags = TestNGUtils.getGroups(result);
          if (TestExclusions.noAutoRetry(testSourceData, testTags)
              && testSourceData.getTestMethod() != null) {
            Test declaration = testSourceData.getTestMethod().getAnnotation(Test.class);
            if (declaration != null
                && IRetryAnalyzer.class.isAssignableFrom(declaration.retryAnalyzer())
                && !RetryAnalyzer.class.equals(declaration.retryAnalyzer())) {
              try {
                frameworkRetryAnalyzer =
                    (IRetryAnalyzer)
                        declaration.retryAnalyzer().getDeclaredConstructor().newInstance();
              } catch (Exception e) {
                LOGGER.debug("Could not instantiate explicit framework retry analyzer", e);
              }
            }
          }
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
    return frameworkRetryAnalyzer != null
            && !result.isSuccess()
            && frameworkRetryAnalyzer.retry(result)
        || executionPolicy.applicable();
  }

  public void setSuppressFailures(ITestResult result) {
    createExecutionPolicy(result);
    suppressFailures = executionPolicy.suppressFailures();
  }

  public boolean shouldPropagateFailure() {
    return executionPolicy != null && executionPolicy.propagateFailure();
  }

  public boolean getAndResetSuppressFailures() {
    boolean suppressFailures = this.suppressFailures;
    this.suppressFailures = false;
    return suppressFailures;
  }

  public TestExecutionTracker getExecutionTracker() {
    return executionPolicy;
  }
}
