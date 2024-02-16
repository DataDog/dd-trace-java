package datadog.trace.instrumentation.junit5.retry;

import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.instrumentation.junit5.JUnitPlatformUtils;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.hierarchical.EngineExecutionContext;
import org.junit.platform.engine.support.hierarchical.Node;
import org.junit.platform.engine.support.hierarchical.ThrowableCollector;

public class RetryContextImpl implements RetryContext {

  private final TestTaskHandle testTask;
  private final TestDescriptorHandle testDescriptor;
  private final EngineExecutionContext parentContext;
  private final TestRetryPolicy retryPolicy;
  private ThrowableCollector throwableCollector;
  private int retryAttemptIdx;

  public RetryContextImpl(TestTaskHandle testTask, TestRetryPolicy retryPolicy) {
    this.testTask = testTask;
    this.parentContext = testTask.getParentContext();
    this.testDescriptor = new TestDescriptorHandle(testTask.getTestDescriptor());
    this.retryPolicy = retryPolicy;
  }

  @Override
  public void prepareRetry() {
    if (retryPolicy.retryPossible() && retryPolicy.suppressFailures()) {
      /*
       * Treat every exception as an assumption error,
       * so that when asked for test execution status,
       * "aborted" is returned instead of "failed".
       * This is needed to avoid failing the build,
       * since the build will fail as long as there are failed tests.
       */
      throwableCollector = new ThrowableCollector(t -> true);
      testTask.setThrowableCollector(throwableCollector);
    } else {
      throwableCollector = testTask.getThrowableCollector();
    }
  }

  @Override
  public void executeRetryIfNeeded() {
    if (!shouldRetry()) {
      return;
    }

    /*
     * Some event listeners (notably the one used by Gradle)
     * require every test execution to have a distinct unique ID.
     * Rerunning a test with the ID that was executed previously will cause errors.
     */
    TestDescriptor retryDescriptor =
        testDescriptor.withIdSuffix(
            RETRY_ATTEMPT_TEST_ID_SEGMENT, String.valueOf(++retryAttemptIdx));
    testTask.setTestDescriptor(retryDescriptor);
    testTask.setNode((Node<?>) retryDescriptor);
    testTask.getListener().dynamicTestRegistered(retryDescriptor);

    // restore parent context, since the reference is overwritten with null after execution
    testTask.setParentContext(parentContext);

    testTask.execute();
  }

  private boolean shouldRetry() {
    Throwable error = throwableCollector.getThrowable();
    boolean success = error == null || JUnitPlatformUtils.isAssumptionFailure(error);
    return retryPolicy.retry(success);
  }
}
