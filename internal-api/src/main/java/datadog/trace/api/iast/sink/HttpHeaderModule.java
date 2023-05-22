package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;

public interface HttpHeaderModule extends IastModule {

  void onHeader(final String name, final String value);
}
