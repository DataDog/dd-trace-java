package com.datadog.iast.sink;

import com.datadog.iast.IastRequestContext;
import com.datadog.iast.model.Evidence;
import com.datadog.iast.model.VulnerabilityType;
import datadog.trace.api.iast.sink.SqlInjectionModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import javax.annotation.Nullable;

public class SqlInjectionModuleImpl extends SinkModuleBase implements SqlInjectionModule {

  @Override
  public void onJdbcQuery(@Nullable final String queryString) {
    onJdbcQuery(queryString, null);
  }

  @Override
  public void onJdbcQuery(@Nullable final String queryString, @Nullable final String database) {
    if (queryString == null) {
      return;
    }
    final AgentSpan span = AgentTracer.activeSpan();
    final IastRequestContext ctx = IastRequestContext.get(span);
    if (ctx == null) {
      return;
    }
    final Evidence evidence =
        checkInjection(span, ctx, VulnerabilityType.SQL_INJECTION, queryString);
    if (evidence != null && database != null) {
      evidence.getContext().put(DATABASE_PARAMETER, database);
    }
  }
}
