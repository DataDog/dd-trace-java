package datadog.trace.api.iast.sink;

import datadog.trace.api.iast.IastModule;
import javax.annotation.Nullable;

public interface SqlInjectionModule extends IastModule {

  String DATABASE_PARAMETER = "DATABASE";

  void onJdbcQuery(@Nullable String sql);

  void onJdbcQuery(@Nullable String sql, @Nullable String database);
}
