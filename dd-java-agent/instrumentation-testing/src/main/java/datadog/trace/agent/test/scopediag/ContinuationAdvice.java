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

  /**
   * {@code activate()} — a (possibly noop) activation; the probe filters the rollback branch.
   *
   * <p>The activation timestamp is captured at method <em>entry</em>, not exit: the same-span reuse
   * optimization ({@code ContinuableScopeManager.continueSpan}) cancels the continuation from
   * <em>inside</em> {@code activate()} before it returns, so timestamping the resume at exit would
   * order it after that internal resolution and spuriously flag {@code ACTIVATE_AFTER_RESOLVE}.
   */
  public static final class Activate {
    @Advice.OnMethodEnter
    public static long enter() {
      return System.nanoTime();
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(
        @Advice.This Object self, @Advice.Enter long ddActivateNanos, @Advice.Return Object scope) {
      ScopeContinuationProbe.onActivate(self, scope, ddActivateNanos);
    }
  }

  /**
   * Resolution detected via the {@code count} transition. Applied to both {@code cancel()} and
   * {@code cancelFromContinuedScopeClose()} — they need identical before/after observation. The
   * originating method name ({@code #m}) distinguishes an explicit cancel from a normal
   * finish-on-scope-close.
   *
   * <p>The resolve timestamp is captured at method <em>entry</em> (the {@code ddResolveNanos}
   * local), not at exit: the body itself may call {@code removeContinuation() ->
   * PendingTrace.write()}, which is exactly where the root-written timestamp is taken. Timestamping
   * at exit would place the resolution after the root write it triggered, producing a spurious
   * late-finish.
   */
  public static final class Cancel {
    @Advice.OnMethodEnter
    public static int enter(
        @Advice.FieldValue("count") int count,
        @Advice.Local("ddResolveNanos") long ddResolveNanos) {
      ddResolveNanos = System.nanoTime();
      return count;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(
        @Advice.This Object self,
        @Advice.Origin("#m") String method,
        @Advice.Enter int countBefore,
        @Advice.Local("ddResolveNanos") long ddResolveNanos,
        @Advice.FieldValue("count") int countAfter) {
      ScopeContinuationProbe.onResolve(self, method, countBefore, countAfter, ddResolveNanos);
    }
  }
}
