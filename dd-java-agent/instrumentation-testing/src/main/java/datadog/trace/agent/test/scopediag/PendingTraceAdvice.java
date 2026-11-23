package datadog.trace.agent.test.scopediag;

import net.bytebuddy.asm.Advice;

/**
 * Records a root write only after {@code PendingTrace.write(boolean)} sets {@code rootSpanWritten}.
 */
public final class PendingTraceAdvice {
  private PendingTraceAdvice() {}

  public static final class Write {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean enter(
        @Advice.Argument(0) boolean isPartial,
        @Advice.FieldValue("rootSpanWritten") boolean alreadyWritten) {
      return !isPartial && !alreadyWritten;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(
        @Advice.Enter boolean candidate,
        @Advice.FieldValue("rootSpanWritten") boolean written,
        @Advice.FieldValue("traceId") Object traceId) {
      if (candidate && written) {
        ScopeContinuationProbe.onRootWritten(traceId);
      }
    }
  }
}
