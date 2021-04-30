package datadog.trace.api;

import static datadog.trace.api.Checkpointer.CPU;
import static datadog.trace.api.Checkpointer.END;
import static datadog.trace.api.Checkpointer.ENQUEUED;
import static datadog.trace.api.Checkpointer.IO;
import static datadog.trace.api.Checkpointer.SPAN;
import static datadog.trace.api.Checkpointer.THREAD_MIGRATION;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public final class SamplingCheckpointer {

  private static final SamplingCheckpointer INSTANCE = new SamplingCheckpointer();

  public static void onComplexEvent(AgentSpan span, int flags) {
    INSTANCE.checkpoint(span, flags);
  }

  public static void onSpanStart(AgentSpan span) {
    INSTANCE.checkpoint(span, SPAN);
  }

  public static void onEnqueue(AgentSpan span) {
    INSTANCE.checkpoint(span, ENQUEUED);
  }

  public static void onCommenceWork(AgentSpan span) {
    INSTANCE.checkpoint(span, CPU);
  }

  public static void onCompleteWork(AgentSpan span) {
    INSTANCE.checkpoint(span, CPU | END);
  }

  public static void onCommenceIO(AgentSpan span) {
    INSTANCE.checkpoint(span, IO);
  }

  public static void onCompleteIO(AgentSpan span) {
    INSTANCE.checkpoint(span, IO | END);
  }

  public static void onThreadMigration(AgentSpan span) {
    INSTANCE.checkpoint(span, THREAD_MIGRATION);
  }

  public static void onAsyncResume(AgentSpan span) {
    INSTANCE.checkpoint(span, THREAD_MIGRATION | END);
  }

  public static void onSpanFinish(AgentSpan span) {
    INSTANCE.checkpoint(span, SPAN | END);
  }

  private void checkpoint(AgentSpan span, int flags) {
    if (sample(span)) {
      AgentSpan.Context context = span.context();
      Checkpointers.get().checkpoint(context.getTraceId(), context.getSpanId(), flags);
    }
  }

  private boolean sample(AgentSpan span) {
    // FIXME use isEligibleForDropping once merged
    return true;
  }
}
