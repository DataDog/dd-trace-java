package datadog.cws.tls;

import datadog.trace.api.DDId;
import datadog.trace.api.scopemanager.ExtendedScopeListener;

public class TlsScopeListener implements ExtendedScopeListener {

  private final TlsSpanTracker spanTracker = new TlsSpanTracker();

  @Override
  public void afterScopeActivated() {
    afterScopeActivated(DDId.ZERO, DDId.ZERO);
  }

  @Override
  public void afterScopeActivated(DDId traceId, DDId spanId) {
    spanTracker.push(traceId, spanId);
  }

  @Override
  public void afterScopeClosed() {
    spanTracker.poll();
  }
}
