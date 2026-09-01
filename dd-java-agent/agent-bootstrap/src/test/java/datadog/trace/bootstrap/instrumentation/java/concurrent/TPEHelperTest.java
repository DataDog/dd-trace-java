package datadog.trace.bootstrap.instrumentation.java.concurrent;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import datadog.context.Context;
import datadog.trace.bootstrap.ContextStore;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;

class TPEHelperTest {

  @Test
  void doesNotInspectQueueWhenThereIsNoContextToCapture() {
    @SuppressWarnings("unchecked")
    ContextStore<Runnable, State> contextStore = mock(ContextStore.class);
    ThreadPoolExecutor executor = mock(ThreadPoolExecutor.class);
    Runnable task = mock(Runnable.class);

    assertSame(task, TPEHelper.captureOrWrap(contextStore, task, Context.root(), executor));

    verify(executor, never()).getQueue();
    verifyNoInteractions(contextStore);
  }
}
