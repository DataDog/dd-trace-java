package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import datadog.trace.api.iast.IastModule.OptOut;
import javax.annotation.Nullable;

@OptOut
public interface StacktraceLeakModule extends IastModule {

  void onStacktraceLeak(
      @Nullable final Throwable expression, String moduleName, String className, String methodName);
}
