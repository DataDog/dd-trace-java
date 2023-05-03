package datadog.trace.instrumentation.kotlin.coroutines;

import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.AbstractCoroutine;
import kotlinx.coroutines.Job;
import org.jetbrains.annotations.Nullable;

public class CoroutineContextHelper {
  /*
  IntelliJ shows a warning here for Job being out of bounds, but that's not true, the class compiles.
   */

  @Nullable
  @SuppressWarnings("unchecked")
  public static Job getJob(final CoroutineContext context) {
    return context.get((CoroutineContext.Key<Job>) Job.Key);
  }

  @Nullable
  public static ScopeStateCoroutineContext getScopeStateContext(final CoroutineContext context) {
    return context.get(ScopeStateCoroutineContext.KEY);
  }

  public static void initializeScopeStateContextIfActive(
      final AbstractCoroutine<?> coroutine, final boolean active) {
    if (active) {
      initializeScopeStateContext(coroutine);
    }
  }

  public static void initializeScopeStateContext(final AbstractCoroutine<?> coroutine) {
    final ScopeStateCoroutineContext scopeStackContext =
        getScopeStateContext(coroutine.getContext());
    if (scopeStackContext != null) {
      scopeStackContext.maybeInitialize(coroutine);
    }
  }

  public static void closeScopeStateContext(final AbstractCoroutine<?> coroutine) {
    final ScopeStateCoroutineContext scopeStackContext =
        getScopeStateContext(coroutine.getContext());
    if (scopeStackContext != null) {
      scopeStackContext.maybeCloseScopeAndCancelContinuation(coroutine);
    }
  }
}
