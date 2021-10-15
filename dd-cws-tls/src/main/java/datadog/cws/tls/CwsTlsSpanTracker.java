package datadog.cws.tls;

import datadog.trace.api.DDId;
import java.util.ArrayDeque;
import java.util.Deque;

public class TlsSpanTracker {

  private final ThreadLocal<Deque<CwsSpan>> spanStack = ThreadLocal.withInitial(ArrayDeque::new);

  private static CwsTls cwsTls = TlsFactory.newTls(4096);

  void push(DDId traceId, DDId spanId) {
    Deque<CwsSpan> stack = spanStack.get();

    CwsSpan top = stack.peek();
    if (top == null
        || top.getTraceId().toLong() != traceId.toLong()
        || top.getSpanId().toLong() != spanId.toLong()) {

      CwsSpan span = new CwsSpan(traceId, spanId);
      stack.push(span);
    }

    cwsTls.registerSpan(traceId, spanId);
  }

  void poll() {
    Deque<CwsSpan> stack = spanStack.get();

    CwsSpan span = stack.poll();
    if (span == null) {
      cwsTls.registerSpan(DDId.ZERO, DDId.ZERO);
    } else {
      cwsTls.registerSpan(span.getTraceId(), span.getSpanId());
    }
  }
}
