package datadog.cws.tls;

import datadog.trace.api.SpanCheckpointer;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public class CwsTlsCheckpointer implements SpanCheckpointer {

  private final CwsTlsSpanTracker spanTracker = new CwsTlsSpanTracker();

  private void onFinish() {
    spanTracker.poll();
  }

  @Override
  public final void checkpoint(final AgentSpan span, final int flags) {}

  @Override
  public final void onStart(final AgentSpan span) {
    spanTracker.push(span.getTraceId(), span.getSpanId());
  }

  @Override
  public final void onStartWork(final AgentSpan span) {}

  @Override
  public final void onFinishWork(final AgentSpan span) {}

  @Override
  public final void onStartThreadMigration(final AgentSpan span) {}

  @Override
  public final void onFinishThreadMigration(final AgentSpan span) {
    spanTracker.push(span.getTraceId(), span.getSpanId());
  }

  @Override
  public final void onFinish(final AgentSpan span) {
    onFinish();
  }

  @Override
  public final void onRootSpan(final AgentSpan root, final boolean published) {
    spanTracker.push(root.getTraceId(), root.getSpanId());
  }
}
