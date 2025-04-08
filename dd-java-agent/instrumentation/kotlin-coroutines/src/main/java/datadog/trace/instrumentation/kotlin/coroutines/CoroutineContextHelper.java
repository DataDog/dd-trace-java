package datadog.trace.instrumentation.kotlin.coroutines;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.AbstractCoroutine;
import kotlinx.coroutines.ChildHandle;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.JobNode;
import kotlinx.coroutines.JobSupport;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CoroutineContextHelper {
  private static final Logger log = LoggerFactory.getLogger(CoroutineContextHelper.class);

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
      scopeStackContext.maybeCloseScopeAndCancelContinuation(coroutine, getParentJob(coroutine));
    }
  }

  private static final MethodHandle PARENT_HANDLE_METHOD;
  private static final MethodHandle PARENT_HANDLE_FIELD;
  private static final MethodHandle JOB_FIELD;

  static {
    MethodHandle parentHandleMethod = null;
    MethodHandle parentHandleField = null;
    MethodHandle jobField = null;

    MethodHandles.Lookup lookup = MethodHandles.publicLookup();
    try {
      // Kotlin coroutines 1.5+
      parentHandleMethod =
          lookup.findVirtual(
              JobSupport.class,
              "getParentHandle$kotlinx_coroutines_core",
              MethodType.methodType(ChildHandle.class));
      jobField = lookup.findGetter(JobNode.class, "job", JobSupport.class);
    } catch (Throwable ignore) {
      try {
        // Kotlin coroutines 1.3
        parentHandleField = lookup.findGetter(JobSupport.class, "parentHandle", ChildHandle.class);
        jobField = lookup.findGetter(JobNode.class, "job", Job.class);
      } catch (Throwable e) {
        log.debug("Unable to access parent handle", e);
      }
    }

    PARENT_HANDLE_METHOD = parentHandleMethod;
    PARENT_HANDLE_FIELD = parentHandleField;
    JOB_FIELD = jobField;
  }

  private static Job getParentJob(JobSupport coroutine) {
    try {
      Object parentHandle = null;
      if (null != PARENT_HANDLE_METHOD) {
        parentHandle = PARENT_HANDLE_METHOD.invoke(coroutine);
      } else if (null != PARENT_HANDLE_FIELD) {
        parentHandle = PARENT_HANDLE_FIELD.invoke(coroutine);
      }
      if (parentHandle instanceof JobNode) {
        return (Job) JOB_FIELD.invoke((JobNode) parentHandle);
      }
    } catch (Throwable e) {
      log.debug("Unable to extract parent job", e);
    }
    return null;
  }
}
