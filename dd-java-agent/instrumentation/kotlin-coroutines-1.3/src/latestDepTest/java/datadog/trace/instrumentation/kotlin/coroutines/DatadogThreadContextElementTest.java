package datadog.trace.instrumentation.kotlin.coroutines;

import static datadog.trace.instrumentation.kotlin.coroutines.DatadogThreadContextElement.addDatadogElement;
import static datadog.trace.instrumentation.kotlin.coroutines.DatadogThreadContextElement.cancelDatadogContext;
import static kotlinx.coroutines.JobKt.Job;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import kotlin.Unit;
import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.AbstractCoroutine;
import kotlinx.coroutines.CompletableJob;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

class DatadogThreadContextElementTest extends AbstractDatadogThreadContextElementTest {
  @Test
  void completionDuringConstructionIgnoresUnassignedContext() {
    CompletableJob parent = Job(null);
    parent.cancel(null);

    // a lazy child of a cancelled parent completes inside the AbstractCoroutine constructor
    CompletedDuringConstruction child = new CompletedDuringConstruction(addDatadogElement(parent));

    assertTrue(child.completedBeforeContextAssigned, "Child did not complete during construction");
    assertNull(child.failure, "Completion handling must tolerate an unassigned context");
  }

  /** Runs the completion advice from onCancelled, which onCompletionInternal calls. */
  private static final class CompletedDuringConstruction extends AbstractCoroutine<Unit> {
    boolean completedBeforeContextAssigned;
    Throwable failure;

    CompletedDuringConstruction(CoroutineContext parentContext) {
      super(parentContext, true, false);
    }

    @Override
    protected void onCancelled(@NotNull Throwable cause, boolean handled) {
      completedBeforeContextAssigned = getContext() == null;
      try {
        cancelDatadogContext(this);
      } catch (Throwable t) {
        failure = t;
      }
    }
  }
}
