package datadog.trace.common.writer;

import datadog.trace.core.CoreSpan;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class CompositePayloadDispatcher implements PayloadDispatcher {

  private final PayloadDispatcher[] delegates;

  public CompositePayloadDispatcher(PayloadDispatcher... delegates) {
    this.delegates = delegates;
  }

  @Override
  public void onDroppedTrace(int spanCount) {
    for (PayloadDispatcher delegate : delegates) {
      delegate.onDroppedTrace(spanCount);
    }
  }

  @Override
  public void addTrace(List<? extends CoreSpan<?>> trace) {
    for (PayloadDispatcher delegate : delegates) {
      delegate.addTrace(trace);
    }
  }

  @Override
  public void flush() {
    for (PayloadDispatcher delegate : delegates) {
      delegate.flush();
    }
  }

  @Override
  public Collection<RemoteApi> getApis() {
    Collection<RemoteApi> apis = new ArrayList<>(delegates.length);
    for (PayloadDispatcher delegate : delegates) {
      apis.addAll(delegate.getApis());
    }
    return apis;
  }
}
