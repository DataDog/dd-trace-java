package datadog.trace.agent.test.scopediag;

import net.bytebuddy.asm.Advice;

/** Test-only advice for {@code ContinuableScope}. */
public final class ContinuableScopeAdvice {
  private ContinuableScopeAdvice() {}

  public static final class OnProperClose {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.This Object scope) {
      ScopeContinuationProbe.onScopeClose(scope);
    }
  }

  public static final class Close {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.This Object scope) {
      ScopeContinuationProbe.onScopeClosing(scope);
    }
  }
}
