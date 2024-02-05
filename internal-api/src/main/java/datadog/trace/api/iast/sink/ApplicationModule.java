package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import datadog.trace.api.iast.IastModule.OptOut;
import javax.annotation.Nullable;

@OptOut
public interface ApplicationModule extends IastModule {

  void onRealPath(@Nullable String realPath);
}
