package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nullable;

public interface StacktraceLeakModule extends IastModule {
  void onResponseException(@Nullable final String expression);
}
