package datadog.cws.tls;

import datadog.trace.api.DDId;
import java.util.ArrayDeque;
import java.util.Deque;

public class TlsSpanTracker {

  private final ThreadLocal<Deque<Span>> spanStack = ThreadLocal.withInitial(ArrayDeque::new);

  private static Tls tls = TlsFactory.newTls(4096);

  void push(DDId traceId, DDId spanId) {
    Deque<Span> stack = spanStack.get();

    Span top = stack.peek();
    if (top == null
        || top.getTraceId().toLong() != traceId.toLong()
        || top.getSpanId().toLong() != spanId.toLong()) {

      Span span = new Span(traceId, spanId);
      stack.push(span);
    }

    tls.registerSpan(traceId, spanId);
  }

  void poll() {
    Deque<Span> stack = spanStack.get();

    Span span = stack.poll();
    if (span == null) {
      tls.registerSpan(DDId.ZERO, DDId.ZERO);
    } else {
      tls.registerSpan(span.getTraceId(), span.getSpanId());
    }
  }

  void threadMigrated(DDId traceId, DDId spanId) {
    tls.registerSpan(span.getTraceId(), span.getSpanId());
  }
}
