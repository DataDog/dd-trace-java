package datadog.trace.agent.test.scopediag;

import net.bytebuddy.asm.Advice;

/** Test-only advice for scopes owned by the iteration cleaner. */
public final class ContinuableScopeManagerAdvice {
  private ContinuableScopeManagerAdvice() {}

  public static final class ScheduleRootIterationCleanup {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.Argument(1) Object scope) {
      ScopeContinuationProbe.onDeferredScopeCleanup(scope);
    }
  }
}
