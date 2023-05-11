package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nullable;

public interface UnvalidatedRedirectModule extends IastModule {

  void onRedirect(@Nullable String value);
}
