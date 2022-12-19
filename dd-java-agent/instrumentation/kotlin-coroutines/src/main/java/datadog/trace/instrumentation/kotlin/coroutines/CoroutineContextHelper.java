package datadog.trace.instrumentation.kotlin.coroutines;

import kotlin.coroutines.CoroutineContext;
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
}
