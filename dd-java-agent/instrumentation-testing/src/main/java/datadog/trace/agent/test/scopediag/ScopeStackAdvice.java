package datadog.trace.agent.test.scopediag;

import net.bytebuddy.asm.Advice;

/** Test-only advice for scopes added to the active stack. */
public final class ScopeStackAdvice {
  private ScopeStackAdvice() {}

  public static final class Push {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(0) Object scope) {
      ScopeContinuationProbe.onScopeOpen(scope);
    }
  }
}
