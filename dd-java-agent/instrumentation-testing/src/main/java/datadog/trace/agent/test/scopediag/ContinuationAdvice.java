package datadog.trace.agent.test.scopediag;

import net.bytebuddy.asm.Advice;

/**
 * Test-only ByteBuddy advice woven into {@code datadog.trace.core.scopemanager.ScopeContinuation}.
 *
 * <p>The target type is package-private and cannot be named here, so {@code this} is typed as
 * {@link Object} and re-cast to the public {@code AgentScope.Continuation} supertype inside {@link
 * ScopeContinuationProbe}. {@link Advice.FieldValue} reads the private {@code count} field — legal
 * because the advice is inlined into the field's own class.
 */
public final class ContinuationAdvice {
  private ContinuationAdvice() {}

  /** {@code register()} — the continuation was captured. */
  public static final class Register {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.This Object self) {
      ScopeContinuationProbe.onCapture(self);
    }
  }

  /** {@code activate()} — a (possibly noop) activation; the probe filters the rollback branch. */
  public static final class Activate {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.This Object self, @Advice.Return Object scope) {
      ScopeContinuationProbe.onActivate(self, scope);
    }
  }

  /** {@code cancel()} — resolution detected via the {@code count} transition. */
  public static final class Cancel {
    @Advice.OnMethodEnter
    public static int enter(@Advice.FieldValue("count") int count) {
      return count;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(
        @Advice.This Object self,
        @Advice.Enter int countBefore,
        @Advice.FieldValue("count") int countAfter) {
      ScopeContinuationProbe.onResolve(self, countBefore, countAfter);
    }
  }

  /** {@code cancelFromContinuedScopeClose()} — same resolution detection as {@link Cancel}. */
  public static final class CancelFromClose {
    @Advice.OnMethodEnter
    public static int enter(@Advice.FieldValue("count") int count) {
      return count;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(
        @Advice.This Object self,
        @Advice.Enter int countBefore,
        @Advice.FieldValue("count") int countAfter) {
      ScopeContinuationProbe.onResolve(self, countBefore, countAfter);
    }
  }
}
