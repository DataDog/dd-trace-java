package datadog.cws.tls;

import datadog.trace.api.DDId;
import datadog.trace.api.SpanCheckpointer;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.ArrayDeque;
import java.util.Deque;

public class CwsTlsCheckpointer implements SpanCheckpointer {

  private final ThreadLocal<Deque<AgentSpan>> spanStack = ThreadLocal.withInitial(ArrayDeque::new);

  private static CwsTls cwsTls = CwsTlsFactory.newCwsTls(4096);

  private void pushEntry(AgentSpan span) {
    Deque<AgentSpan> stack = spanStack.get();

    AgentSpan top = stack.peek();
    if (top == null
        || top.getTraceId().toLong() != span.getTraceId().toLong()
        || top.getSpanId().toLong() != span.getSpanId().toLong()) {
      stack.push(span);
    }

    cwsTls.registerSpan(span.getTraceId(), span.getSpanId());
  }

  private void pollEntry() {
    Deque<AgentSpan> stack = spanStack.get();

    AgentSpan span = stack.poll();
    if (span == null) {
      cwsTls.registerSpan(DDId.ZERO, DDId.ZERO);
    } else {
      cwsTls.registerSpan(span.getTraceId(), span.getSpanId());
    }
  }

  private void onFinish() {
    pollEntry();
  }

  @Override
  public final void checkpoint(final AgentSpan span, final int flags) {}

  @Override
  public final void onStart(final AgentSpan span) {
    pushEntry(span);
  }

  @Override
  public final void onStartWork(final AgentSpan span) {}

  @Override
  public final void onFinishWork(final AgentSpan span) {}

  @Override
  public final void onStartThreadMigration(final AgentSpan span) {}

  @Override
  public final void onFinishThreadMigration(final AgentSpan span) {
    pushEntry(span);
  }

  @Override
  public final void onFinish(final AgentSpan span) {
    onFinish();
  }

  @Override
  public final void onRootSpan(final AgentSpan root, final boolean published) {
    pushEntry(root);
  }
}
