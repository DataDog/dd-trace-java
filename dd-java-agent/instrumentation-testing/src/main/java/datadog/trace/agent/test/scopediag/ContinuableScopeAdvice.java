package datadog.trace.agent.test.scopediag;

import net.bytebuddy.asm.Advice;

/**
 * Test-only ByteBuddy advice woven into {@code datadog.trace.core.scopemanager.ContinuableScope}
 * (and, by inheritance, {@code ContinuingScope}) to track the scope activation lifecycle.
 *
 * <p>The target type is package-private, so {@code this} is typed as {@link Object} and re-cast
 * inside {@link ScopeContinuationProbe}. {@code afterActivated} is the open point (first call per
 * scope identity), {@code onProperClose} the pop, and {@code close} the wrong-thread check.
 */
public final class ContinuableScopeAdvice {
  private ContinuableScopeAdvice() {}

  /** {@code afterActivated()} — the scope became active. */
  public static final class AfterActivated {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.This Object scope) {
      ScopeContinuationProbe.onScopeOpen(scope);
    }
  }

  /** {@code onProperClose()} — the scope was popped from its thread's stack. */
  public static final class OnProperClose {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.This Object scope) {
      ScopeContinuationProbe.onScopeClose(scope);
    }
  }

  /** {@code close()} — check for an out-of-order / wrong-thread close. */
  public static final class Close {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.This Object scope) {
      ScopeContinuationProbe.onScopeClosing(scope);
    }
  }
}
