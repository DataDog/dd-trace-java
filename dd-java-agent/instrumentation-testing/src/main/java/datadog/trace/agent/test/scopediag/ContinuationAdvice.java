package datadog.trace.agent.test.scopediag;

import net.bytebuddy.asm.Advice;

/** Test-only advice for {@code ScopeContinuation}. */
public final class ContinuationAdvice {
  private ContinuationAdvice() {}

  public static final class Register {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.This Object self) {
      ScopeContinuationProbe.onCapture(self);
    }
  }

  /** Timestamps entry because {@code resume()} may resolve the continuation before returning. */
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

  /** Timestamps entry because resolution may write the trace before the method returns. */
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
