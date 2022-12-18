package datadog.trace.instrumentation.kotlin.coroutines;

import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.Job;
import org.jetbrains.annotations.Nullable;

public class CoroutineContextHelper {
  @Nullable
  @SuppressWarnings({"rawtypes", "unchecked"})
  public static Job getJob(final CoroutineContext context) {
    return (Job) context.get((CoroutineContext.Key) Job.Key);
  }
}
