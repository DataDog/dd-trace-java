package datadog.cws.tls;

import datadog.trace.api.DD128bTraceId;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.scopemanager.ExtendedScopeListener;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Deque;

public class TlsScopeListener implements ExtendedScopeListener {

  private final ThreadLocal<Deque<Span>> spanStack = ThreadLocal.withInitial(ArrayDeque::new);

  private Tls tls;

  public TlsScopeListener() {
    tls = TlsFactory.newTls(4096);
  }

  public TlsScopeListener(Tls tls) {
    this.tls = tls;
  }

  void push(DDTraceId traceId, long spanId) {
    Deque<Span> stack = spanStack.get();
    BigInteger spanId128b = BigInteger.valueOf(spanId);

    Span top = stack.peek();
    if (top == null
        || top.getTraceId().toLong() != traceId.toLong()
        || top.getTraceId().toHighOrderLong() != traceId.toHighOrderLong()
        || top.getSpanId().longValue() != spanId) {
      Span span = new Span(traceId, spanId128b);
      stack.push(span);
    }

    tls.registerSpan(traceId, spanId128b);
  }

  void poll() {
    Deque<Span> stack = spanStack.get();

    Span span = stack.poll();
    if (span != null) {
      Span parent = stack.peek();
      if (parent != null) {
        tls.registerSpan(parent.getTraceId(), parent.getSpanId());
        return;
      }
    }
    tls.registerSpan(DD128bTraceId.ZERO, BigInteger.ZERO);
  }

  @Override
  public void afterScopeActivated() {
    afterScopeActivated(DDTraceId.ZERO, DDSpanId.ZERO, DDSpanId.ZERO, null);
  }

  @Override
  public void afterScopeActivated(
      DDTraceId traceId, long localRootSpanId, long spanId, TraceConfig traceConfig) {
    push(traceId, spanId);
  }

  @Override
  public void afterScopeClosed() {
    poll();
  }
}
