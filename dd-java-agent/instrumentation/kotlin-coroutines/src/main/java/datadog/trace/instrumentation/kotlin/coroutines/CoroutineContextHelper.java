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
  public static DatadogCoroutineContext getDatadogContext(final CoroutineContext context) {
    return context.get(DatadogCoroutineContext.KEY);
  }

  public static void initializeDatadogContextIfActive(
      final AbstractCoroutine<?> coroutine, final boolean active) {
    if (active) {
      initializeDatadogContext(coroutine);
    }
  }

  public static void initializeDatadogContext(final AbstractCoroutine<?> coroutine) {
    final DatadogCoroutineContext datadogContext = getDatadogContext(coroutine.getContext());
    if (datadogContext != null) {
      datadogContext.maybeInitialize(coroutine);
    }
  }

  public static void closeDatadogContext(final AbstractCoroutine<?> coroutine) {
    final DatadogCoroutineContext datadogContext = getDatadogContext(coroutine.getContext());
    if (datadogContext != null) {
      datadogContext.maybeCloseScopeAndCancelContinuation(coroutine);
    }
  }
}
