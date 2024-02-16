package datadog.trace.instrumentation.junit5.retry;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.api.civisibility.retry.TestRetryPolicy;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.instrumentation.junit5.TestEventsHandlerHolder;
import datadog.trace.instrumentation.junit5.TestIdentifierFactory;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.hierarchical.HierarchicalTestExecutorService;

public class RetryContextFactory
    implements ContextStore.KeyAwareFactory<
        HierarchicalTestExecutorService.TestTask, RetryContext> {

  public static final RetryContextFactory INSTANCE = new RetryContextFactory();

  @Override
  public RetryContext create(HierarchicalTestExecutorService.TestTask testTask) {
    if (TestEventsHandlerHolder.TEST_EVENTS_HANDLER == null) {
      return NoOpRetryContext.INSTANCE;
    }

    TestTaskHandle testTaskHandle = new TestTaskHandle(testTask);
    TestDescriptor testDescriptor = testTaskHandle.getTestDescriptor();
    TestIdentifier testIdentifier =
        TestIdentifierFactory.createTestIdentifier(
            testDescriptor,
            /* backend cannot provide parameters for flaky parameterized tests yet */ false);
    TestRetryPolicy retryPolicy =
        TestEventsHandlerHolder.TEST_EVENTS_HANDLER.retryPolicy(testIdentifier);
    if (retryPolicy.retryPossible()) {
      return new RetryContextImpl(testTaskHandle, retryPolicy);
    } else {
      return NoOpRetryContext.INSTANCE;
    }
  }
}
