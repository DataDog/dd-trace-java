package datadog.trace.api.iast.sink;

import datadog.trace.api.gateway.IGSpanInfo;
import datadog.trace.api.iast.IastModule;

public interface XContentTypeOptionsModule extends IastModule {
  void onRequestEnd(Object iastRequestContext, IGSpanInfo igSpanInfo);
}
