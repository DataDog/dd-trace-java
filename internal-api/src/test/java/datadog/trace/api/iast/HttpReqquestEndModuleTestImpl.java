package datadog.trace.api.iast;

import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.iast.sink.HttpRequestEndModule;

public class HttpReqquestEndModuleTestImpl implements HttpRequestEndModule {
  @Override
  public void onRequestEnd(IastContext ctx, IGSpanInfo span) {}
}
