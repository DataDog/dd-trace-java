package datadog.trace.agent.test.scopediag;

import net.bytebuddy.asm.Advice;

/**
 * Test-only ByteBuddy advice woven into {@code datadog.trace.core.PendingTrace}. Fires the
 * root-written signal after {@code write(boolean)} actually changes {@code rootSpanWritten} from
 * false to true. Observing the completed transition avoids treating an empty write as a root write.
 * The timestamp is conservative: a resolution racing inside {@code write} may be omitted from the
 * late category, but it cannot be falsely classified as late.
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
