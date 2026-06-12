package datadog.trace.agent.test.scopediag;

import net.bytebuddy.asm.Advice;

/**
 * Test-only ByteBuddy advice woven into {@code datadog.trace.core.PendingTrace}. Fires the
 * root-written signal at the exact site where the old production seam did: inside {@code
 * write(boolean)} on the non-partial path, just before {@code rootSpanWritten} is set, gated on it
 * not already being set. {@link Advice.FieldValue} reads the private {@code traceId}/{@code
 * rootSpanWritten} fields — legal because the advice is inlined into their own class.
 */
public final class PendingTraceAdvice {
  private PendingTraceAdvice() {}

  public static final class Write {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.Argument(0) boolean isPartial,
        @Advice.FieldValue("rootSpanWritten") boolean alreadyWritten,
        @Advice.FieldValue("traceId") Object traceId) {
      if (!isPartial && !alreadyWritten) {
        ScopeContinuationProbe.onRootWritten(traceId);
      }
    }
  }
}
