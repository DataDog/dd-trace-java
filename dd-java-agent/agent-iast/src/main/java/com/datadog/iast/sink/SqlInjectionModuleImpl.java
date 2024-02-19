package com.datadog.iast.sink;

import static com.datadog.iast.taint.Tainteds.canBeTainted;

import com.datadog.iast.Dependencies;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.sink.SqlInjectionModule;
import javax.annotation.Nullable;

public class SqlInjectionModuleImpl extends SinkModuleBase implements SqlInjectionModule {

  public SqlInjectionModuleImpl(final Dependencies dependencies) {
    super(dependencies);
  }

  @Override
  public void onJdbcQuery(@Nullable final String queryString) {
    onJdbcQuery(queryString, null);
  }

  @Override
  public void onJdbcQuery(@Nullable final String queryString, @Nullable final String database) {
    if (!canBeTainted(queryString)) {
      return;
    }
    final Evidence evidence = checkInjection(VulnerabilityType.SQL_INJECTION, queryString);
    if (evidence != null && database != null) {
      evidence.getContext().put(DATABASE_PARAMETER, database);
    }
  }
}
