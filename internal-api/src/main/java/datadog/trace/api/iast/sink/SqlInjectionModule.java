package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nullable;

public interface SqlInjectionModule extends IastModule {

  void onJdbcQuery(@Nullable String queryString);
}
