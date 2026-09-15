import datadog.trace.test.junit.utils.config.WithConfig;

/**
 * Runs the async-handler context-retention reproducer with completion-priority propagation, which
 * associates the completing context with the resolved {@code Try} instead of the thread.
 *
 * <p>{@link WithConfig} applies before the test agent is installed, so the Scala Promise
 * instrumentation registers the advice that creates that association.
 */
@WithConfig(key = "trace.integration.scala_promise_completion_priority.enabled", value = "true")
class PekkoHttpAsyncHandlerWrapperForkedTest extends AbstractPekkoHttpAsyncHandlerWrapperTest {

  @Override
  protected boolean expectedCompletionPriority() {
    return true;
  }
}
