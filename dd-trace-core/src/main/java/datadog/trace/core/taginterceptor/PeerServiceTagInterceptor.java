package datadog.trace.core.taginterceptor;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.ExclusiveSpan;

class PeerServiceTagInterceptor extends AbstractTagInterceptor {
  public PeerServiceTagInterceptor() {
    super(Tags.PEER_SERVICE);
  }

  @Override
  public boolean shouldSetTag(final ExclusiveSpan span, final String tag, final Object value) {
    span.setServiceName(String.valueOf(value));
    return false;
  }
}
