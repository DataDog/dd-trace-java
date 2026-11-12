package datadog.trace.instrumentation.akka.concurrent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.config.TraceInstrumentationConfig;
import datadog.trace.test.junit.utils.config.WithConfig;
import org.junit.jupiter.api.Test;

@WithConfig(key = TraceInstrumentationConfig.LEGACY_CONTEXT_MANAGER_ENABLED, value = "false")
class ActorScopeCleanupForkedTest extends AbstractInstrumentationTest {

  @Test
  void actorExitClosesTaskScopeWhenEntryFailedBeforeSavingContext() {
    RecordingScope taskScope = new RecordingScope();

    assertDoesNotThrow(() -> AkkaActorCellInstrumentation.InvokeAdvice.exit(taskScope, null));

    assertTrue(taskScope.closed);
  }

  @Test
  void mailboxExitToleratesEntryFailureBeforeSavingContext() {
    assertDoesNotThrow(() -> AkkaMailboxInstrumentation.SuppressMailboxRunAdvice.exit(null));
  }

  private static final class RecordingScope implements ContextScope {
    private boolean closed;

    @Override
    public Context context() {
      return Context.root();
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
