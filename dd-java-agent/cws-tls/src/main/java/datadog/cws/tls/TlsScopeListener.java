package datadog.cws.tls;

import datadog.trace.api.DDId;
import datadog.trace.api.scopemanager.ExtendedScopeListener;
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
    if (span != null) {
      Span parent = stack.peek();
      if (parent != null) {
        tls.registerSpan(parent.getTraceId(), parent.getSpanId());
        return;
      }
    }
    tls.registerSpan(DDId.ZERO, DDId.ZERO);
  }

  @Override
  public void afterScopeActivated() {
    afterScopeActivated(DDId.ZERO, DDId.ZERO);
  }

  @Override
  public void afterScopeActivated(DDId traceId, DDId spanId) {
    push(traceId, spanId);
  }

  @Override
  public void afterScopeClosed() {
    poll();
  }
}
